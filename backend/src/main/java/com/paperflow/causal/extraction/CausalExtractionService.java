package com.paperflow.causal.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.extraction.CausalBatchStore.RunRow;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public final class CausalExtractionService {
    public static final Set<String> ARTIFACT_ALLOWLIST = Set.of(
            "input.csv", "stage0_raw_responses.jsonl", "stage1_raw_responses.jsonl",
            "final_claims.csv", "mapped_results.csv", "result.json", "worker.log");

    private final CausalBatchStore store;
    private final CausalExtractor extractor;
    private final CausalBatchRunner runner;
    private final CausalEventHub events;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final CausalTargetValidator targets;
    private final boolean enabled;
    private final IngestionMode defaultMode;
    private final int maxCalls;
    private final int maxBatchTargets;

    public CausalExtractionService(
            CausalBatchStore store, CausalExtractor extractor, CausalBatchRunner runner, CausalEventHub events,
            AuditLog audit, TransactionTemplate transactions, ObjectMapper json,
            boolean enabled, IngestionMode defaultMode, int maxCalls, int maxBatchTargets
    ) {
        this.store = store; this.extractor = extractor; this.runner = runner; this.events = events;
        this.audit = audit; this.transactions = transactions; this.json = json;
        this.targets = new CausalTargetValidator(maxBatchTargets);
        this.enabled = enabled; this.defaultMode = defaultMode; this.maxCalls = maxCalls; this.maxBatchTargets = maxBatchTargets;
    }

    public EstimateResponse estimate(EstimateRequest request) {
        TargetSpec target = targets.validate(request == null ? null : request.target());
        IngestionMode mode = IngestionMode.parse(request == null ? null : request.ingestionMode(), defaultMode);
        List<TargetCandidate> candidates = targets.enforceCap(store.resolveTargets(target, maxBatchTargets + 1));
        Map<String, Long> skipReasons = new TreeMap<>();
        int executable = 0;
        for (TargetCandidate c : candidates) {
            if (c.skipReason() == null) executable++;
            else skipReasons.merge(c.skipReason(), 1L, Long::sum);
        }
        String version = enabled ? safeHealth().profileVersion() : "disabled";
        return new EstimateResponse(enabled, candidates.size(), executable, skipReasons,
                version, mode.name(), maxCalls, maxBatchTargets);
    }

    public CausalBatchView create(String actor, CreateBatchRequest request) {
        requireEnabled();
        TargetSpec target = targets.validate(request == null ? null : request.target());
        IngestionMode mode = IngestionMode.parse(request == null ? null : request.ingestionMode(), defaultMode);
        List<TargetCandidate> candidates = targets.enforceCap(store.resolveTargets(target, maxBatchTargets + 1));
        if (candidates.isEmpty()) throw new IllegalArgumentException("未匹配到任何目标论文");
        if (candidates.stream().noneMatch(c -> c.skipReason() == null)) {
            throw new CausalConflict("NO_EXECUTABLE_TARGETS", "所选论文均不满足抽取前置条件（需未归档、已关联 OpenAlex 且已发布全文）");
        }
        UUID batchId = UUID.randomUUID();
        List<RunRow> runs = candidates.stream().map(c -> new RunRow(
                UUID.randomUUID(), batchId, c.paperId(), c.workId(), c.sourceId(), null, null,
                c.skipReason() == null ? RunStatus.QUEUED : RunStatus.SKIPPED, RunStage.PREPARE, "{}")).toList();
        persistBatch(actor, batchId, mode, target, candidates, runs);
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

    private void persistBatch(
            String actor, UUID batchId, IngestionMode mode, TargetSpec target,
            List<TargetCandidate> candidates, List<RunRow> runs
    ) {
        String scopeJson = writeJson(targets.scopeSummary(target, candidates.size()));
        String configJson = writeJson(targets.configSnapshot(safeHealth(), mode, maxCalls));
        try {
            transactions.executeWithoutResult(s -> {
                store.createBatch(batchId, mode, scopeJson, configJson, runs);
                for (int i = 0; i < candidates.size(); i++) {
                    String reason = candidates.get(i).skipReason();
                    if (reason != null) {
                        store.markFailed(runs.get(i).runId(), reason, CausalTargetValidator.skipMessage(reason), "{}");
                    }
                }
                audit.record(actor, "CAUSAL_BATCH_CREATE", "CAUSAL_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED,
                        Map.of("mode", mode.name(), "total", candidates.size()));
            });
        } catch (DataIntegrityViolationException duplicate) {
            throw new CausalConflict("WORK_ACTIVE_EXTRACTION", "目标作品已有进行中的因果抽取，请等待完成后重试");
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new CausalUnavailable("CAUSAL_EXTRACTION_DISABLED", "因果抽取尚未启用，请先完成引擎与配置部署");
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

    private EngineHealth safeHealth() {
        try { return extractor.health(); } catch (Exception e) { return new EngineHealth(false, "unknown", "", "", maxCalls, "引擎不可达"); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
