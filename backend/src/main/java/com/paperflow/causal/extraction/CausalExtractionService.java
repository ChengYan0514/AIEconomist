package com.paperflow.causal.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.extraction.CausalBatchStore.RunRow;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.*;
import com.paperflow.settings.ServiceSettings.CausalConfig;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

public final class CausalExtractionService {
    public static final Set<String> ARTIFACT_ALLOWLIST = Set.of(
            "input.csv", "stage0_raw_responses.jsonl", "stage1_raw_responses.jsonl",
            "final_claims.csv", "mapped_results.csv", "result.json", "worker.log");

    private final CausalBatchStore store;
    private final CausalExtractor extractor;
    private final CausalBatchRunner runner;
    private final CausalPayloadValidator validator;
    private final CausalEventHub events;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final CausalTargetValidator targets;
    private final Supplier<CausalConfig> config;
    private final int maxBatchTargets;

    public CausalExtractionService(
            CausalBatchStore store, CausalExtractor extractor, CausalBatchRunner runner, CausalPayloadValidator validator,
            CausalEventHub events, AuditLog audit, TransactionTemplate transactions, ObjectMapper json,
            Supplier<CausalConfig> config, int maxBatchTargets
    ) {
        this.store = store; this.extractor = extractor; this.runner = runner; this.validator = validator; this.events = events;
        this.audit = audit; this.transactions = transactions; this.json = json;
        this.targets = new CausalTargetValidator(maxBatchTargets);
        this.config = config; this.maxBatchTargets = maxBatchTargets;
    }

    public EstimateResponse estimate(EstimateRequest request) {
        CausalConfig current = config.get();
        TargetSpec target = targets.validate(request == null ? null : request.target());
        IngestionMode mode = IngestionMode.parse(request == null ? null : request.ingestionMode(), current.defaultMode());
        List<TargetCandidate> candidates = targets.enforceCap(store.resolveTargets(target, maxBatchTargets + 1));
        Map<String, Long> skipReasons = new TreeMap<>();
        int executable = 0;
        for (TargetCandidate c : candidates) {
            if (c.skipReason() == null) executable++;
            else skipReasons.merge(c.skipReason(), 1L, Long::sum);
        }
        String version = current.enabled() ? safeHealth(current.maxCalls()).profileVersion() : "disabled";
        return new EstimateResponse(current.enabled(), candidates.size(), executable, skipReasons,
                version, mode.name(), current.maxCalls(), maxBatchTargets);
    }

    public CausalBatchView create(String actor, CreateBatchRequest request) {
        CausalConfig current = requireEnabled();
        TargetSpec target = targets.validate(request == null ? null : request.target());
        IngestionMode mode = IngestionMode.parse(request == null ? null : request.ingestionMode(), current.defaultMode());
        ReviewPolicy reviewPolicy = ReviewPolicy.parse(request == null ? null : request.reviewPolicy(), ReviewPolicy.MANUAL_REVIEW);
        List<TargetCandidate> candidates = targets.enforceCap(store.resolveTargets(target, maxBatchTargets + 1));
        if (candidates.isEmpty()) throw new IllegalArgumentException("未匹配到任何目标论文");
        if (candidates.stream().noneMatch(c -> c.skipReason() == null)) {
            throw new CausalConflict("NO_EXECUTABLE_TARGETS", "所选论文均不满足抽取前置条件（需未归档、已关联 OpenAlex 且已发布全文）");
        }
        UUID batchId = UUID.randomUUID();
        List<RunRow> runs = candidates.stream().map(c -> new RunRow(
                UUID.randomUUID(), batchId, c.paperId(), c.workId(), c.sourceId(), null, null,
                c.skipReason() == null ? RunStatus.QUEUED : RunStatus.SKIPPED, RunStage.PREPARE, "{}", null)).toList();
        persistBatch(actor, batchId, mode, reviewPolicy, target, candidates, runs);
        runner.dispatch(batchId, actor);
        return requireBatchView(batchId);
    }

    public Map<String, Object> list(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        return Map.of("items", store.listBatchViews(safePage * safeSize, safeSize),
                "page", safePage, "size", safeSize, "total", store.countBatches());
    }

    public CausalBatchDetail detail(UUID batchId, String statusFilter, int page, int size) {
        CausalBatchView batch = requireBatchView(batchId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String status = statusFilter == null || statusFilter.isBlank() ? null : statusFilter.strip().toUpperCase(Locale.ROOT);
        return new CausalBatchDetail(batch, store.listRunViews(batchId, status, safePage * safeSize, safeSize),
                safePage, safeSize, store.countRunViews(batchId, status));
    }

    public CausalBatchView cancel(String actor, UUID batchId) {
        requireBatchView(batchId);
        boolean changed = Boolean.TRUE.equals(transactions.execute(s -> {
            if (!store.cancelBatch(batchId)) return false;
            audit.record(actor, "CAUSAL_BATCH_CANCEL", "CAUSAL_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED, Map.of());
            return true;
        }));
        if (!changed) throw new CausalConflict("BATCH_NOT_CANCELLABLE", "当前批次状态不允许取消");
        runner.cancel(batchId);
        return requireBatchView(batchId);
    }

    public CausalBatchView retry(String actor, UUID batchId) {
        requireEnabled();
        requireBatchView(batchId);
        try {
            int count = Objects.requireNonNullElse(transactions.execute(s -> {
                int requeued = store.requeueRetryable(batchId);
                if (requeued > 0) {
                    audit.record(actor, "CAUSAL_BATCH_RETRY", "CAUSAL_BATCH", batchId.toString(),
                            AuditOutcome.SUCCEEDED, Map.of("requeued", requeued));
                }
                return requeued;
            }), 0);
            if (count == 0) throw new CausalConflict("NOTHING_TO_RETRY", "没有可重试的失败项");
        } catch (DataIntegrityViolationException duplicate) {
            throw new CausalConflict("WORK_ACTIVE_EXTRACTION", "目标作品已被其他活跃抽取任务占用");
        }
        runner.dispatch(batchId, actor);
        return requireBatchView(batchId);
    }

    public List<ArtifactInfo> artifacts(UUID runId) throws IOException, InterruptedException {
        RunRow run = requireRun(runId);
        if (run.engineRunId() == null || run.engineRunId().isBlank()) return List.of();
        return extractor.artifacts(run.engineRunId()).stream()
                .filter(a -> ARTIFACT_ALLOWLIST.contains(a.name())).toList();
    }

    public InputStream openArtifact(UUID runId, String name) throws IOException, InterruptedException {
        if (name == null || !ARTIFACT_ALLOWLIST.contains(name)) throw new IllegalArgumentException("不支持下载该产物文件");
        RunRow run = requireRun(runId);
        if (run.engineRunId() == null || run.engineRunId().isBlank()) {
            throw new CausalNotFound("ARTIFACT_NOT_FOUND", "该运行尚未生成可下载产物");
        }
        return extractor.openArtifact(run.engineRunId(), name);
    }

    public SseEmitter subscribeEvents(UUID batchId) {
        requireBatchView(batchId);
        return events.subscribe(batchId);
    }

    public List<RunReviewDraft> getBatchReviewDrafts(UUID batchId) {
        requireBatchView(batchId);
        return store.listPendingReviewDrafts(batchId);
    }

    public RunReviewDraft getRunReviewDraft(UUID runId) {
        RunReviewDraft draft = store.getRunReviewDraft(runId);
        if (draft == null) throw new CausalNotFound("CAUSAL_RUN_NOT_FOUND", "待审核记录不存在");
        return draft;
    }

    public void dismissRunReview(String actor, UUID runId) {
        RunRow run = requireRun(runId);
        if (run.status() != RunStatus.PENDING_REVIEW) {
            throw new CausalConflict("RUN_NOT_PENDING_REVIEW", "当前记录不处于待审核状态");
        }
        transactions.executeWithoutResult(s -> {
            store.dismissReview(runId);
            audit.record(actor, "CAUSAL_RUN_REVIEW_DISMISSED", "CAUSAL_RUN", runId.toString(), AuditOutcome.SUCCEEDED,
                    Map.of("batchId", run.batchId().toString(), "workId", run.workId()));
            store.finalizeBatchIfComplete(run.batchId());
        });
        runner.publisher().emit(run.batchId(), run, RunStatus.CANCELLED, run.stage(), RunMetrics.empty(), "CANCELLED");
    }

    public void submitRunReview(String actor, UUID runId, List<EditableClaimItem> claims) {
        RunRow run = requireRun(runId);
        if (run.status() != RunStatus.PENDING_REVIEW) {
            throw new CausalConflict("RUN_NOT_PENDING_REVIEW", "当前记录不处于待审核状态");
        }
        var blocks = runner.publisher().verifyBlocks(run);
        String currentInputHash = validator.inputHash(run.paperId(), run.workId(), run.sourceId(), blocks);
        RunMetrics originalMetrics = parseMetrics(run.metricsJson());
        var validated = validator.parseAndValidateEditedClaims(run.workId(), claims, originalMetrics);
        runner.publisher().publishValidatedClaims(run.batchId(), run, currentInputHash, validated, actor);
    }

    public void submitBatchReview(String actor, UUID batchId, List<RunReviewDraft> drafts) {
        requireBatchView(batchId);
        if (drafts == null || drafts.isEmpty()) return;
        for (RunReviewDraft item : drafts) {
            if (item.runId() != null) {
                submitRunReview(actor, item.runId(), item.claims());
            }
        }
    }

    private RunMetrics parseMetrics(String jsonStr) {
        try { return jsonStr == null ? RunMetrics.empty() : json.readValue(jsonStr, RunMetrics.class); }
        catch (Exception e) { return RunMetrics.empty(); }
    }

    private void persistBatch(
            String actor, UUID batchId, IngestionMode mode, ReviewPolicy reviewPolicy, TargetSpec target,
            List<TargetCandidate> candidates, List<RunRow> runs
    ) {
        int currentMaxCalls = config.get().maxCalls();
        String scopeJson = writeJson(targets.scopeSummary(target, candidates.size()));
        String configJson = writeJson(targets.configSnapshot(safeHealth(currentMaxCalls), mode, currentMaxCalls));
        try {
            transactions.executeWithoutResult(s -> {
                store.createBatch(batchId, mode, reviewPolicy, scopeJson, configJson, runs);
                for (int i = 0; i < candidates.size(); i++) {
                    String reason = candidates.get(i).skipReason();
                    if (reason != null) {
                        store.markFailed(runs.get(i).runId(), reason, CausalTargetValidator.skipMessage(reason), "{}");
                    }
                }
                audit.record(actor, "CAUSAL_BATCH_CREATE", "CAUSAL_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED,
                        Map.of("mode", mode.name(), "reviewPolicy", reviewPolicy.name(), "total", candidates.size()));
            });
        } catch (DataIntegrityViolationException duplicate) {
            throw new CausalConflict("WORK_ACTIVE_EXTRACTION", "目标作品已有进行中的因果抽取，请等待完成后重试");
        }
    }

    private CausalConfig requireEnabled() {
        CausalConfig current = config.get();
        if (!current.enabled()) {
            throw new CausalUnavailable("CAUSAL_EXTRACTION_DISABLED", "因果抽取尚未启用，请前往「系统管理 → 服务配置」完成引擎与模型配置并开启");
        }
        return current;
    }

    private CausalBatchView requireBatchView(UUID batchId) {
        CausalBatchView view = store.findBatchView(batchId);
        if (view == null) throw new CausalNotFound("CAUSAL_BATCH_NOT_FOUND", "因果抽取批次不存在");
        return view;
    }

    private RunRow requireRun(UUID runId) {
        RunRow row = store.findRun(runId);
        if (row == null) throw new CausalNotFound("CAUSAL_RUN_NOT_FOUND", "单篇抽取记录不存在");
        return row;
    }

    private EngineHealth safeHealth(int maxCalls) {
        try { return extractor.health(); } catch (Exception e) { return new EngineHealth(false, "unknown", "", "", maxCalls, "引擎不可达"); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
