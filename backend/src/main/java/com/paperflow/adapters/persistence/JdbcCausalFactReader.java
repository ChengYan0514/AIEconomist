package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.paperflow.adapters.persistence.CausalFactSql.*;

public final class JdbcCausalFactReader implements CausalFactReader {
    private final JdbcTemplate jdbc;

    public JdbcCausalFactReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public long datasetRevision() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('causal_dataset_revision') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) return 0L;
        List<Long> rows = jdbc.queryForList("SELECT revision FROM causal_dataset_revision WHERE id = 1", Long.class);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Override
    public OverviewStats overview(int minRecordCount) {
        return jdbc.queryForObject("""
                WITH edge_counts AS MATERIALIZED (SELECT claim_id, COUNT(*) AS cnt FROM paper_claim_table GROUP BY claim_id),
                eligible AS MATERIALIZED (SELECT claim_id FROM edge_counts WHERE cnt >= ?),
                g_vars AS (SELECT ct.cause_standard AS v FROM claim_table ct JOIN eligible e ON e.claim_id = ct.claim_id
                           UNION SELECT ct.effect_standard FROM claim_table ct JOIN eligible e ON e.claim_id = ct.claim_id),
                all_vars AS (SELECT cause_standard AS v FROM claim_table UNION SELECT effect_standard FROM claim_table)
                SELECT COALESCE((SELECT SUM(cnt) FROM edge_counts), 0), (SELECT COUNT(*) FROM claim_table),
                       (SELECT COUNT(DISTINCT paper_id) FROM paper_claim_table),
                       (SELECT COUNT(*) FROM all_vars), (SELECT COUNT(*) FROM g_vars), (SELECT COUNT(*) FROM eligible)
                """, (rs, i) -> new OverviewStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6)), minRecordCount);
    }

    @Override
    public List<String> listMethods() {
        return jdbc.queryForList("""
                SELECT DISTINCT TRIM(causal_inference_method) FROM paper_claim_table
                WHERE causal_inference_method IS NOT NULL AND TRIM(causal_inference_method) <> '' ORDER BY 1
                """, String.class);
    }

    @Override
    public List<EdgeProjectionRow> listAllEdgeProjections() {
        return jdbc.query(EDGE_SQL + " GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard "
                + "ORDER BY COUNT(*) DESC, COUNT(DISTINCT pct.paper_id) DESC, ct.claim_id ASC",
                (rs, i) -> new EdgeProjectionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10), stringList(rs, 12)));
    }

    @Override
    public List<PaperClaimPair> listPaperClaimPairs() {
        return jdbc.query("""
                SELECT pct.paper_id, ct.claim_id, ct.cause_standard, ct.effect_standard, COUNT(*)
                FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
                GROUP BY pct.paper_id, ct.claim_id, ct.cause_standard, ct.effect_standard
                """, (rs, i) -> new PaperClaimPair(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5)));
    }

    @Override
    public List<NamedCountRow> listGlobalMethodCounts() {
        return jdbc.query("""
                SELECT COALESCE(NULLIF(TRIM(causal_inference_method), ''), '未标注方法') AS n, COUNT(*) AS c
                FROM paper_claim_table GROUP BY 1 ORDER BY c DESC, n ASC
                """, (rs, i) -> new NamedCountRow(rs.getString(1), rs.getLong(2)));
    }

    @Override
    public List<NamedCountRow> listGlobalVariableCounts(int limit) {
        return jdbc.query("""
                WITH vars AS (
                    SELECT ct.cause_standard AS v, pct.record_id FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
                    UNION ALL
                    SELECT ct.effect_standard AS v, pct.record_id FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
                )
                SELECT v, COUNT(DISTINCT record_id) AS c FROM vars WHERE v IS NOT NULL AND TRIM(v) <> ''
                GROUP BY v ORDER BY c DESC, v ASC LIMIT ?
                """, (rs, i) -> new NamedCountRow(rs.getString(1), rs.getLong(2)), limit);
    }

    @Override
    public List<GlobalRelationRow> listGlobalRelations(int limit) {
        return jdbc.query("""
                SELECT ct.cause_standard, ct.effect_standard, COUNT(*) AS c, COUNT(DISTINCT pct.paper_id) AS p,
                       COUNT(DISTINCT NULLIF(TRIM(pct.causal_inference_method), '')) AS m
                FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
                GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard
                ORDER BY c DESC, m DESC, p DESC, ct.cause_standard ASC, ct.effect_standard ASC LIMIT ?
                """, (rs, i) -> new GlobalRelationRow(rs.getString(1), rs.getString(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5)), limit);
    }

    @Override
    public EdgeAggregate findEdgeByClaimId(long claimId) {
        var rows = jdbc.query(EDGE_SQL + " WHERE ct.claim_id = ? GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard",
                EDGE_ROW, claimId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public EdgeAggregate findEdgeByCauseEffect(String cause, String effect) {
        var rows = jdbc.query(EDGE_SQL + " WHERE ct.cause_standard = ? AND ct.effect_standard = ? "
                + "GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard ORDER BY COUNT(*) DESC, ct.claim_id ASC LIMIT 1",
                EDGE_ROW, cause, effect);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<EdgeAggregate> listOutgoingEdges(String variable, int limit) {
        return jdbc.query(EDGE_SQL + " WHERE ct.cause_standard = ? GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard "
                + "ORDER BY COUNT(*) DESC, ct.effect_standard ASC LIMIT ?", EDGE_ROW, variable, limit);
    }

    @Override
    public List<EdgeAggregate> listIncomingEdges(String variable, int limit) {
        return jdbc.query(EDGE_SQL + " WHERE ct.effect_standard = ? GROUP BY ct.claim_id, ct.cause_standard, ct.effect_standard "
                + "ORDER BY COUNT(*) DESC, ct.cause_standard ASC LIMIT ?", EDGE_ROW, variable, limit);
    }

    @Override
    public List<String> listWorkIdsForVariable(String variable) {
        return jdbc.queryForList("""
                SELECT pct.paper_id FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
                WHERE ct.cause_standard = ? OR ct.effect_standard = ?
                """, String.class, variable, variable);
    }

    @Override
    public List<RawClaimRow> listClaimsByClaimId(long claimId, int limit) {
        return jdbc.query(CLAIM_SQL + " WHERE ct.claim_id = ? ORDER BY pct.record_id DESC LIMIT ?", CLAIM_ROW, claimId, limit);
    }

    @Override
    public List<RawClaimRow> listClaimsByCauseEffect(String cause, String effect, int limit) {
        return jdbc.query(CLAIM_SQL + " WHERE ct.cause_standard = ? AND ct.effect_standard = ? ORDER BY pct.record_id DESC LIMIT ?",
                CLAIM_ROW, cause, effect, limit);
    }

    @Override
    public List<RawClaimRow> listClaimsByWorkId(String workId) {
        return jdbc.query(CLAIM_SQL + " WHERE pct.paper_id = ? ORDER BY pct.record_id ASC", CLAIM_ROW, workId);
    }

    @Override
    public PaperSummaryRow findPaperSummary(String workId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*), COUNT(DISTINCT pct.claim_id),
                       (SELECT COUNT(*) FROM (
                           SELECT ct.cause_standard AS v FROM paper_claim_table p JOIN claim_table ct ON ct.claim_id = p.claim_id WHERE p.paper_id = ? AND ct.cause_standard IS NOT NULL
                           UNION SELECT ct.effect_standard FROM paper_claim_table p JOIN claim_table ct ON ct.claim_id = p.claim_id WHERE p.paper_id = ? AND ct.effect_standard IS NOT NULL
                       ) vars)
                FROM paper_claim_table pct WHERE pct.paper_id = ?
                """, (rs, i) -> new PaperSummaryRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), workId, workId, workId);
    }

    @Override
    public List<String> searchTerms(String query, int limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('stw_label') IS NOT NULL", Boolean.class))) return List.of();
        String escaped = escapeLike(query);
        return jdbc.queryForList(SEARCH_TERMS_SQL, String.class, "%" + escaped + "%", escaped + "%", limit);
    }

    @Override
    public List<NamedCountRow> listPaperClaimCounts() {
        return jdbc.query("SELECT paper_id, COUNT(*) FROM paper_claim_table GROUP BY paper_id",
                (rs, i) -> new NamedCountRow(rs.getString(1), rs.getLong(2)));
    }
}
