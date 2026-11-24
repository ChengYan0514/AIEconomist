package com.paperflow.causal.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.extraction.CausalBatchStore.BatchRow;
import com.paperflow.causal.extraction.CausalBatchStore.RunRow;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.graph.CausalReadModelRefresher;
import com.paperflow.causal.model.RunStage;
import com.paperflow.causal.model.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class CausalBatchRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CausalBatchRunner.class);
    private static final List<String> KNOWN_CODES = List.of(
            "LEGACY_DUPLICATE_RELATION", "PUBLICATION_HASH_CONFLICT", "INVALID_RESULT",
            "INPUT_CHANGED", "NO_PUBLISHED_BLOCKS", "EXECUTION_TIMEOUT", "MODEL_CALL_BUDGET_EXCEEDED");

    private final CausalBatchStore store;
    private final CausalExtractor extractor;
    private final CausalPayloadValidator validator;
    private final CausalPublicationExecutor publisher;
    private final AuditLog audit;
    private final ExecutorService executor;
    private final Duration timeout;
    private final int maxCalls;
    private final Map<UUID, Future<?>> inFlight = new ConcurrentHashMap<>();

    public CausalBatchRunner(
            CausalBatchStore store, CausalExtractor extractor, CausalFactWriter writer,
            CausalPayloadValidator validator, CausalReadModelRefresher readModel,
            CausalEventHub events, AuditLog audit, ObjectMapper json,
            int maxConcurrency, Duration timeout, int maxCalls
    ) {
        this.store = store;
        this.extractor = extractor;
        this.validator = validator;
        this.publisher = new CausalPublicationExecutor(store, writer, validator, readModel, events, audit, json);
        this.audit = audit;
        this.timeout = timeout;
        this.maxCalls = maxCalls;
        this.executor = Executors.newFixedThreadPool(maxConcurrency, task -> {
            Thread thread = new Thread(task, "causal-extraction-runner");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void dispatch(UUID batchId, String actor) {
        BatchRow batch = store.findBatch(batchId);
        if (batch == null || batch.cancelled()) return;
        for (RunRow run : store.listQueuedRuns(batchId)) {
            if (!inFlight.containsKey(run.runId())) {
                inFlight.put(run.runId(), executor.submit(() -> executeRun(batch, run, actor)));
            }
        }
    }

    public void cancel(UUID batchId) {
        for (RunRow run : store.listActiveRuns(batchId)) {
            Future<?> future = inFlight.remove(run.runId());
            if (future != null) future.cancel(true);
            if (run.engineRunId() != null && !run.engineRunId().isBlank()) {
                try { extractor.cancel(run.engineRunId()); }
                catch (Exception ignored) { log.debug("Ignoring engine cancel error for {}", run.engineRunId()); }
            }
        }
        store.finalizeBatchIfComplete(batchId);
    }

    public void reconcileOnStartup() {
        for (RunRow run : store.listInterruptedRuns()) {
            store.markFailed(run.runId(), "INTERRUPTED", "服务重启导致运行中断，可重试继续", run.metricsJson());
            store.finalizeBatchIfComplete(run.batchId());
        }
    }

    private void executeRun(BatchRow batch, RunRow run, String actor) {
        try {
            if (isCancelled(batch.batchId())) return;
            var blocks = publisher.verifyBlocks(run);
            String inputHash = validator.inputHash(run.paperId(), run.workId(), run.sourceId(), blocks);
            if (run.inputHash() != null && !run.inputHash().equals(inputHash)) {
                throw new IllegalStateException("INPUT_CHANGED: 论文全文或元数据已变化，请新建抽取批次");
            }
            EngineRunStatus terminal = obtainEngineResult(batch, run, blocks, inputHash);
            if (isCancelled(batch.batchId())) return;
            publisher.publish(batch.batchId(), run, inputHash, terminal, actor);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            handleFailure(batch.batchId(), run, actor, "CANCELLED", "运行已中断");
        } catch (Exception error) {
            String code = extractCode(error.getMessage());
            String message = code != null ? error.getMessage() : "因果抽取执行失败，请检查引擎日志或重试";
            if (code == null) log.warn("Causal extraction run {} failed", run.runId(), error);
            handleFailure(batch.batchId(), run, actor, code == null ? "EXTRACTION_FAILED" : code, message);
        } finally {
            inFlight.remove(run.runId());
            store.finalizeBatchIfComplete(batch.batchId());
        }
    }

    private EngineRunStatus obtainEngineResult(
            BatchRow batch, RunRow run, List<BlockExportRow> blocks, String inputHash
    ) throws IOException, InterruptedException {
        if (run.engineRunId() != null && !run.engineRunId().isBlank()) {
            EngineRunStatus existing = safePoll(run.engineRunId());
            if (existing != null && "SUCCEEDED".equals(existing.status()) && existing.resultJson() != null) return existing;
        }
        String engineId = extractor.submit(run.runId(), run.workId(), batch.mode(), maxCalls, run.engineRunId(), blocks);
        if (isCancelled(batch.batchId())) {
            extractor.cancel(engineId);
            throw new InterruptedException("Cancelled");
        }
        store.markRunning(run.runId(), inputHash, engineId, RunStage.PREPARE);
        publisher.emit(batch.batchId(), run, RunStatus.RUNNING, RunStage.PREPARE, RunMetrics.empty(), null);
        return awaitEngine(batch.batchId(), run, engineId);
    }

    private EngineRunStatus awaitEngine(UUID batchId, RunRow run, String engineId) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RunStage lastStage = RunStage.PREPARE;
        while (Instant.now().isBefore(deadline)) {
            if (Thread.currentThread().isInterrupted() || isCancelled(batchId)) {
                extractor.cancel(engineId);
                throw new InterruptedException("Cancelled");
            }
            EngineRunStatus status = extractor.poll(engineId);
            RunStage stage = RunStage.fromEngine(status.stage());
            if (stage != lastStage) {
                lastStage = stage;
                store.updateProgress(run.runId(), stage, publisher.writeJson(status.usage()));
                publisher.emit(batchId, run, RunStatus.RUNNING, stage, status.usage(), null);
            }
            if ("SUCCEEDED".equals(status.status())) return status;
            if ("CANCELLED".equals(status.status())) throw new InterruptedException("Engine cancelled");
            if ("FAILED".equals(status.status())) {
                String code = status.errorCode() == null || status.errorCode().isBlank() ? "ENGINE_FAILED" : status.errorCode();
                throw new IllegalStateException(code + ": " + Objects.requireNonNullElse(status.errorMessage(), "引擎执行失败"));
            }
            TimeUnit.MILLISECONDS.sleep(400);
        }
        extractor.cancel(engineId);
        throw new IllegalStateException("EXECUTION_TIMEOUT: 因果抽取超时，可重试复用已完成阶段");
    }

    private void handleFailure(UUID batchId, RunRow run, String actor, String code, String message) {
        if ("CANCELLED".equals(code) || isCancelled(batchId)) {
            publisher.emit(batchId, run, RunStatus.CANCELLED, run.stage(), RunMetrics.empty(), "CANCELLED");
            return;
        }
        store.markFailed(run.runId(), code, message, run.metricsJson());
        audit.record(actor, "CAUSAL_RUN_FAILED", "CAUSAL_RUN", run.runId().toString(), AuditOutcome.FAILED,
                Map.of("batchId", batchId.toString(), "workId", Objects.toString(run.workId(), ""), "code", code));
        publisher.emit(batchId, run, RunStatus.FAILED, run.stage(), RunMetrics.empty(), code);
    }

    private EngineRunStatus safePoll(String engineRunId) {
        try { return extractor.poll(engineRunId); } catch (Exception ignored) { return null; }
    }

    private boolean isCancelled(UUID batchId) {
        BatchRow latest = store.findBatch(batchId);
        return latest == null || latest.cancelled();
    }

    private static String extractCode(String message) {
        if (message == null) return null;
        int colon = message.indexOf(':');
        if (colon > 0 && colon <= 64) {
            String prefix = message.substring(0, colon).strip();
            if (prefix.matches("[A-Z0-9_]{3,64}")) return prefix;
        }
        for (String code : KNOWN_CODES) if (message.startsWith(code)) return code;
        return null;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
