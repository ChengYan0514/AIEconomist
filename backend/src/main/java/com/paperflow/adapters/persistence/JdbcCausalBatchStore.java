package com.paperflow.adapters.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalBatchStore;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.causal.model.RunStage;
import com.paperflow.causal.model.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

public final class JdbcCausalBatchStore implements CausalBatchStore {
    private static final String RUN_COLS = """
            run_id, batch_id, paper_id, work_id, source_id, input_hash, engine_run_id, status, stage, metrics::text
            """;
    private static final RowMapper<RunRow> RUN_ROW = (rs, i) -> new RunRow(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), RunStatus.valueOf(rs.getString(8)), RunStage.valueOf(rs.getString(9)), rs.getString(10));

    private final JdbcTemplate jdbc;
    private final JdbcCausalBatchQuery query;
    private final JdbcCausalTargetQuery targets;

    public JdbcCausalBatchStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.query = new JdbcCausalBatchQuery(jdbc, json);
        this.targets = new JdbcCausalTargetQuery(jdbc);
    }

    @Override public List<TargetCandidate> resolveTargets(TargetSpec target, int limitPlusOne) { return targets.resolveTargets(target, limitPlusOne); }
    @Override public List<BlockExportRow> loadPublishedBlocks(String paperId) { return targets.loadPublishedBlocks(paperId); }
    @Override public boolean isPaperEligible(String paperId, String workId, String sourceId) { return targets.isPaperEligible(paperId, workId, sourceId); }
    @Override public CausalBatchView findBatchView(UUID batchId) { return query.findBatchView(batchId); }
    @Override public List<CausalBatchView> listBatchViews(int offset, int size) { return query.listBatchViews(offset, size); }
    @Override public long countBatches() { return query.countBatches(); }
    @Override public List<CausalRunView> listRunViews(UUID batchId, String statusFilter, int offset, int size) { return query.listRunViews(batchId, statusFilter, offset, size); }
    @Override public long countRunViews(UUID batchId, String statusFilter) { return query.countRunViews(batchId, statusFilter); }

    @Override
    public void createBatch(UUID batchId, IngestionMode mode, String scopeJson, String configJson, List<RunRow> runs) {
        jdbc.update("INSERT INTO v2_causal_batch (batch_id, ingestion_mode, scope, config_snapshot) VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                batchId, mode.name(), scopeJson, configJson);
        for (RunRow run : runs) {
            jdbc.update("""
                    INSERT INTO v2_causal_run (run_id, batch_id, paper_id, work_id, source_id, status, stage, metrics)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, run.runId(), batchId, run.paperId(), run.workId(), run.sourceId(),
                    run.status().name(), run.stage().name(), run.metricsJson());
        }
    }

    @Override
    public BatchRow findBatch(UUID batchId) {
        var rows = jdbc.query("SELECT batch_id, ingestion_mode, scope::text, config_snapshot::text, cancelled FROM v2_causal_batch WHERE batch_id = ?",
                (rs, i) -> new BatchRow(rs.getObject(1, UUID.class), IngestionMode.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getString(4), rs.getBoolean(5)), batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public RunRow findRun(UUID runId) {
        var rows = jdbc.query("SELECT " + RUN_COLS + " FROM v2_causal_run WHERE run_id = ?", RUN_ROW, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<RunRow> listQueuedRuns(UUID batchId) {
        return jdbc.query("SELECT " + RUN_COLS + " FROM v2_causal_run WHERE batch_id = ? AND status = 'QUEUED' ORDER BY paper_id", RUN_ROW, batchId);
    }

    @Override
    public List<RunRow> listActiveRuns(UUID batchId) {
        return jdbc.query("SELECT " + RUN_COLS + " FROM v2_causal_run WHERE batch_id = ? AND status IN ('QUEUED','RUNNING','PUBLISHING')", RUN_ROW, batchId);
    }

    @Override
    public List<RunRow> listInterruptedRuns() {
        return jdbc.query("SELECT " + RUN_COLS + " FROM v2_causal_run WHERE status IN ('RUNNING','PUBLISHING')", RUN_ROW);
    }

    @Override
    public void markRunning(UUID runId, String inputHash, String engineRunId, RunStage stage) {
        jdbc.update("UPDATE v2_causal_run SET status = 'RUNNING', input_hash = ?, engine_run_id = ?, stage = ?, error_code = NULL, error_message = NULL, updated_at = now() WHERE run_id = ? AND status = 'QUEUED'",
                inputHash, engineRunId, stage.name(), runId);
    }

    @Override
    public void updateProgress(UUID runId, RunStage stage, String metricsJson) {
        jdbc.update("UPDATE v2_causal_run SET stage = ?, metrics = CAST(? AS jsonb), updated_at = now() WHERE run_id = ? AND status = 'RUNNING'",
                stage.name(), metricsJson, runId);
    }

    @Override
    public void markPublishing(UUID runId, String metricsJson) {
        jdbc.update("UPDATE v2_causal_run SET status = 'PUBLISHING', stage = 'PUBLISH', metrics = CAST(? AS jsonb), updated_at = now() WHERE run_id = ? AND status = 'RUNNING'",
                metricsJson, runId);
    }

    @Override
    public void markSucceeded(UUID runId, String metricsJson) {
        jdbc.update("UPDATE v2_causal_run SET status = 'SUCCEEDED', stage = 'PUBLISH', metrics = CAST(? AS jsonb), error_code = NULL, error_message = NULL, updated_at = now(), finished_at = now() WHERE run_id = ? AND status = 'PUBLISHING'",
                metricsJson, runId);
    }

    @Override
    public void markFailed(UUID runId, String errorCode, String errorMessage, String metricsJson) {
        jdbc.update("UPDATE v2_causal_run SET status = CASE WHEN status = 'SKIPPED' THEN 'SKIPPED' ELSE 'FAILED' END, error_code = ?, error_message = ?, metrics = CAST(? AS jsonb), updated_at = now(), finished_at = now() WHERE run_id = ?",
                errorCode, errorMessage, metricsJson, runId);
    }

    @Override
    public boolean cancelBatch(UUID batchId) {
        if (jdbc.update("UPDATE v2_causal_batch SET cancelled = true WHERE batch_id = ? AND NOT cancelled AND finished_at IS NULL", batchId) == 0) return false;
        jdbc.update("UPDATE v2_causal_run SET status = 'CANCELLED', updated_at = now(), finished_at = now() WHERE batch_id = ? AND status IN ('QUEUED','RUNNING')", batchId);
        return true;
    }

    @Override
    public int requeueRetryable(UUID batchId) {
        int count = jdbc.update("UPDATE v2_causal_run SET status = 'QUEUED', error_code = NULL, error_message = NULL, updated_at = now(), finished_at = NULL WHERE batch_id = ? AND status = 'FAILED'", batchId);
        if (count > 0) jdbc.update("UPDATE v2_causal_batch SET cancelled = false, finished_at = NULL WHERE batch_id = ?", batchId);
        return count;
    }

    @Override
    public void finalizeBatchIfComplete(UUID batchId) {
        jdbc.update("""
                UPDATE v2_causal_batch b SET finished_at = now()
                WHERE b.batch_id = ? AND b.finished_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM v2_causal_run r WHERE r.batch_id = b.batch_id AND r.status IN ('QUEUED','RUNNING','PUBLISHING'))
                """, batchId);
    }
}
