package com.paperflow.causal.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.extraction.CausalBatchStore.RunRow;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.graph.CausalReadModelRefresher;
import com.paperflow.causal.model.RunStage;
import com.paperflow.causal.model.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CausalPublicationExecutor {
    private final CausalBatchStore store;
    private final CausalFactWriter writer;
    private final CausalPayloadValidator validator;
    private final CausalReadModelRefresher readModel;
    private final CausalEventHub events;
    private final AuditLog audit;
    private final ObjectMapper json;

    CausalPublicationExecutor(
            CausalBatchStore store, CausalFactWriter writer, CausalPayloadValidator validator,
            CausalReadModelRefresher readModel, CausalEventHub events, AuditLog audit, ObjectMapper json
    ) {
        this.store = store;
        this.writer = writer;
        this.validator = validator;
        this.readModel = readModel;
        this.events = events;
        this.audit = audit;
        this.json = json;
    }

    List<BlockExportRow> verifyBlocks(RunRow run) {
        if (!store.isPaperEligible(run.paperId(), run.workId(), run.sourceId())) {
            throw new IllegalStateException("INPUT_CHANGED: 论文状态或 OpenAlex 关联已变化");
        }
        var blocks = store.loadPublishedBlocks(run.paperId());
        if (blocks.isEmpty()) throw new IllegalStateException("NO_PUBLISHED_BLOCKS: 缺少可用的已发布全文内容块");
        return blocks;
    }

    void publish(UUID batchId, RunRow run, String inputHash, EngineRunStatus terminal, String actor) {
        if (terminal.resultJson() == null) throw new IllegalStateException("INVALID_RESULT: 引擎未返回结构化抽取结果");
        var validated = validator.parseAndValidate(run.workId(), terminal.resultJson());
        writer.checkNoLegacyDuplicates(run.workId(), validated.rows());
        var currentBlocks = verifyBlocks(run);
        if (!inputHash.equals(validator.inputHash(run.paperId(), run.workId(), run.sourceId(), currentBlocks))) {
            throw new IllegalStateException("INPUT_CHANGED: 发布前检测到全文或关联已变化，已保留产物未入库");
        }
        String metricsJson = writeJson(validated.metrics());
        store.markPublishing(run.runId(), metricsJson);
        emit(batchId, run, RunStatus.PUBLISHING, RunStage.PUBLISH, validated.metrics(), null);
        var outcome = writer.publishIdempotently(run.runId(), run.workId(), validated.payloadHash(), validated.rows());
        store.markSucceeded(run.runId(), metricsJson);
        audit.record(actor, "CAUSAL_RUN_PUBLISHED", "CAUSAL_RUN", run.runId().toString(), AuditOutcome.SUCCEEDED,
                Map.of("batchId", batchId.toString(), "workId", run.workId(),
                        "claims", outcome.rowCount(), "alreadyApplied", outcome.alreadyApplied()));
        emit(batchId, run, RunStatus.SUCCEEDED, RunStage.PUBLISH, validated.metrics(), null);
        readModel.refreshAsync();
    }

    void emit(UUID batchId, RunRow run, RunStatus status, RunStage stage, RunMetrics metrics, String code) {
        var view = store.findBatchView(batchId);
        events.publish(new RunEvent(batchId, run.runId(), run.paperId(), run.workId(), status.name(), stage.name(),
                metrics == null ? RunMetrics.empty() : metrics, code, view == null ? "RUNNING" : view.status(), Instant.now()));
    }

    String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? RunMetrics.empty() : value); }
        catch (Exception error) { return "{}"; }
    }
}
