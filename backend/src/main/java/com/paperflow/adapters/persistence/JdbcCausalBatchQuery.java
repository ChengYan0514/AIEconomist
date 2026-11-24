package com.paperflow.adapters.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

final class JdbcCausalBatchQuery {
    private static final String BATCH_VIEW_SQL = """
            SELECT b.batch_id, b.ingestion_mode, b.review_policy, b.cancelled, b.scope::text, b.config_snapshot::text,
                   b.created_at, b.finished_at,
                   COUNT(r.run_id) AS total,
                   COUNT(*) FILTER (WHERE r.status = 'QUEUED') AS queued,
                   COUNT(*) FILTER (WHERE r.status = 'RUNNING') AS running,
                   COUNT(*) FILTER (WHERE r.status = 'PENDING_REVIEW') AS pending_review,
                   COUNT(*) FILTER (WHERE r.status = 'PUBLISHING') AS publishing,
                   COUNT(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded,
                   COUNT(*) FILTER (WHERE r.status = 'SUCCEEDED' AND COALESCE((r.metrics->>'claimCount')::int, 0) = 0) AS zero_claims,
                   COUNT(*) FILTER (WHERE r.status = 'SKIPPED') AS skipped,
                   COUNT(*) FILTER (WHERE r.status = 'FAILED') AS failed,
                   COUNT(*) FILTER (WHERE r.status = 'CANCELLED') AS cancelled_count,
                   COALESCE(SUM(COALESCE((r.metrics->>'claimCount')::int, 0)) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS total_claims,
                   COALESCE(SUM(COALESCE((r.metrics->>'callsUsed')::int, 0)), 0) AS calls_used
            FROM v2_causal_batch b LEFT JOIN v2_causal_run r ON r.batch_id = b.batch_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcCausalBatchQuery(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    CausalBatchView findBatchView(UUID batchId) {
        var rows = jdbc.query(BATCH_VIEW_SQL + " WHERE b.batch_id = ? GROUP BY b.batch_id", this::mapBatchView, batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<CausalBatchView> listBatchViews(int offset, int size) {
        return jdbc.query(BATCH_VIEW_SQL + " GROUP BY b.batch_id ORDER BY b.created_at DESC, b.batch_id DESC LIMIT ? OFFSET ?",
                this::mapBatchView, size, offset);
    }

    long countBatches() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM v2_causal_batch", Long.class);
        return count == null ? 0L : count;
    }

    List<CausalRunView> listRunViews(UUID batchId, String statusFilter, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.run_id, r.batch_id, r.paper_id, r.work_id, r.source_id, p.title,
                       r.status, r.stage, r.metrics::text, r.error_code, r.error_message,
                       (r.engine_run_id IS NOT NULL AND r.engine_run_id <> '') AS has_artifacts,
                       r.updated_at, r.finished_at
                FROM v2_causal_run r LEFT JOIN v2_paper_read p ON p.paper_id = r.paper_id
                WHERE r.batch_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(batchId);
        if (statusFilter != null) { sql.append(" AND r.status = ?"); params.add(statusFilter); }
        sql.append(" ORDER BY r.updated_at DESC, r.run_id ASC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(offset);
        return jdbc.query(sql.toString(), this::mapRunView, params.toArray());
    }

    long countRunViews(UUID batchId, String statusFilter) {
        Long count = statusFilter == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM v2_causal_run WHERE batch_id = ?", Long.class, batchId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM v2_causal_run WHERE batch_id = ? AND status = ?", Long.class, batchId, statusFilter);
        return count == null ? 0L : count;
    }

    private CausalBatchView mapBatchView(ResultSet rs, int index) throws SQLException {
        boolean cancelled = rs.getBoolean("cancelled");
        int q = rs.getInt("queued"), r = rs.getInt("running"), pr = rs.getInt("pending_review"), p = rs.getInt("publishing");
        int s = rs.getInt("succeeded"), f = rs.getInt("failed"), c = rs.getInt("cancelled_count");
        boolean active = q + r + pr + p > 0;
        return new CausalBatchView(rs.getObject("batch_id", UUID.class), rs.getString("ingestion_mode"),
                rs.getString("review_policy"),
                deriveStatus(cancelled, q, r, pr, p, s, f, c), cancelled,
                readMap(rs.getString("scope")), readMap(rs.getString("config_snapshot")),
                rs.getInt("total"), q, r, pr, p, s, rs.getInt("zero_claims"),
                rs.getInt("skipped"), f, c, rs.getInt("total_claims"), rs.getInt("calls_used"),
                active && !cancelled, !active && f > 0, pr > 0,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    }

    private String deriveStatus(boolean cancelled, int q, int r, int pr, int p, int s, int f, int c) {
        if (r + p > 0 || pr > 0) return "RUNNING";
        if (q > 0) return "QUEUED";
        if (cancelled && s == 0 && f == 0) return "CANCELLED";
        if (f > 0 && s > 0) return "PARTIAL";
        if (f > 0) return "FAILED";
        if (cancelled) return "CANCELLED";
        return "SUCCEEDED";
    }

    private CausalRunView mapRunView(ResultSet rs, int index) throws SQLException {
        return new CausalRunView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), readMetrics(rs.getString(9)),
                rs.getString(10), rs.getString(11), rs.getBoolean(12),
                rs.getTimestamp(13).toInstant(), rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant());
    }

    List<RunReviewDraft> listPendingReviewDrafts(UUID batchId) {
        return jdbc.query("""
                SELECT r.run_id, r.batch_id, r.paper_id, r.work_id, p.title, r.status, r.draft_payload::text
                FROM v2_causal_run r LEFT JOIN v2_paper_read p ON p.paper_id = r.paper_id
                WHERE r.batch_id = ? AND r.status = 'PENDING_REVIEW'
                ORDER BY r.updated_at ASC
                """, this::mapRunReviewDraft, batchId);
    }

    RunReviewDraft getRunReviewDraft(UUID runId) {
        var rows = jdbc.query("""
                SELECT r.run_id, r.batch_id, r.paper_id, r.work_id, p.title, r.status, r.draft_payload::text
                FROM v2_causal_run r LEFT JOIN v2_paper_read p ON p.paper_id = r.paper_id
                WHERE r.run_id = ?
                """, this::mapRunReviewDraft, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RunReviewDraft mapRunReviewDraft(ResultSet rs, int index) throws SQLException {
        return new RunReviewDraft(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                parseDraftClaims(rs.getString(7))
        );
    }

    private List<EditableClaimItem> parseDraftClaims(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return json.readValue(jsonStr, new TypeReference<List<EditableClaimItem>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String raw) {
        try { return raw == null ? Map.of() : json.readValue(raw, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private RunMetrics readMetrics(String raw) {
        try { return raw == null ? RunMetrics.empty() : json.readValue(raw, RunMetrics.class); }
        catch (Exception e) { return RunMetrics.empty(); }
    }
}
