package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader.NamedCountRow;
import com.paperflow.causal.graph.CausalReadModelStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

public final class JdbcCausalReadModelStore implements CausalReadModelStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcCausalWorkMetaQuery metaQuery;

    public JdbcCausalReadModelStore(JdbcTemplate jdbc, TransactionTemplate transactions, String openalexSchema) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.metaQuery = new JdbcCausalWorkMetaQuery(jdbc, openalexSchema);
    }

    @Override
    public SnapshotRow findSnapshot(String key) {
        var rows = jdbc.query("""
                SELECT snapshot_key, revision, payload::text, refreshed_at
                FROM v2_causal_snapshot WHERE snapshot_key = ?
                """, (rs, i) -> new SnapshotRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void replaceReadModel(List<EdgeIndexRow> edges, List<SnapshotRow> snapshots) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM v2_causal_edge_index");
            if (!edges.isEmpty()) batchInsertEdges(edges);
            for (SnapshotRow snap : snapshots) {
                jdbc.update("""
                        INSERT INTO v2_causal_snapshot (snapshot_key, revision, payload, refreshed_at)
                        VALUES (?, ?, CAST(? AS jsonb), ?)
                        ON CONFLICT (snapshot_key) DO UPDATE SET revision = EXCLUDED.revision,
                            payload = EXCLUDED.payload, refreshed_at = EXCLUDED.refreshed_at
                        """, snap.key(), snap.revision(), snap.payload(), Timestamp.from(snap.refreshedAt()));
            }
        });
    }

    @Override
    public List<EdgeIndexRow> queryEdges(GraphQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT claim_id, cause_standard, effect_standard, record_count, paper_count, diversity,
                       positive_count, negative_count, null_count, mixed_count, subfields, revision
                FROM v2_causal_edge_index WHERE record_count >= ? AND diversity >= ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(query.minRecordCount());
        params.add(query.minDiversity());
        if (query.minPaperCount() != null) { sql.append(" AND paper_count >= ?"); params.add(query.minPaperCount()); }
        if (query.query() != null) {
            sql.append(" AND (lower(cause_standard) LIKE ? ESCAPE '\\' OR lower(effect_standard) LIKE ? ESCAPE '\\')");
            String like = "%" + CausalFactSql.escapeLike(query.query()) + "%";
            params.add(like);
            params.add(like);
        }
        if (!query.subfields().isEmpty()) {
            sql.append(" AND subfields && ?::text[]");
            params.add(JdbcCausalWorkMetaQuery.textArrayParam(query.subfields().toArray(String[]::new)));
        }
        sql.append(" ORDER BY record_count DESC, paper_count DESC, claim_id ASC LIMIT ?");
        params.add(query.limit());
        return jdbc.query(sql.toString(), (rs, i) -> new EdgeIndexRow(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                stringList(rs.getArray(11)), rs.getLong(12)), params.toArray());
    }

    @Override
    public List<NamedCountRow> searchVariables(String query, int limit) {
        String escaped = CausalFactSql.escapeLike(query);
        String contains = "%" + escaped + "%";
        String prefix = escaped + "%";
        return jdbc.query("""
                SELECT variable, SUM(cnt) AS total FROM (
                    SELECT cause_standard AS variable, SUM(record_count) AS cnt FROM v2_causal_edge_index
                    WHERE lower(cause_standard) LIKE ? ESCAPE '\\' GROUP BY cause_standard
                    UNION ALL
                    SELECT effect_standard AS variable, SUM(record_count) AS cnt FROM v2_causal_edge_index
                    WHERE lower(effect_standard) LIKE ? ESCAPE '\\' GROUP BY effect_standard
                ) h
                GROUP BY variable
                ORDER BY CASE WHEN lower(variable) LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, total DESC, variable ASC
                LIMIT ?
                """, (rs, i) -> new NamedCountRow(rs.getString(1), rs.getLong(2)), contains, contains, prefix, limit);
    }

    @Override public Map<String, WorkMeta> loadWorkMeta(Collection<String> workIds) { return metaQuery.loadWorkMeta(workIds); }
    @Override public Map<String, List<WorkTopicRow>> loadWorkTopics(Collection<String> workIds) { return metaQuery.loadWorkTopics(workIds); }
    @Override public List<WorkMeta> searchWorkMeta(String query, Collection<String> ids, int limit) { return metaQuery.searchWorkMeta(query, ids, limit); }

    private void batchInsertEdges(List<EdgeIndexRow> edges) {
        String sql = """
                INSERT INTO v2_causal_edge_index (claim_id, cause_standard, effect_standard, record_count,
                    paper_count, diversity, positive_count, negative_count, null_count, mixed_count, subfields, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.batchUpdate(sql, edges, 500, (ps, row) -> {
            ps.setLong(1, row.claimId());
            ps.setString(2, row.causeStandard());
            ps.setString(3, row.effectStandard());
            ps.setLong(4, row.recordCount());
            ps.setLong(5, row.paperCount());
            ps.setLong(6, row.diversity());
            ps.setLong(7, row.positiveCount());
            ps.setLong(8, row.negativeCount());
            ps.setLong(9, row.nullCount());
            ps.setLong(10, row.mixedCount());
            ps.setArray(11, textArray(ps.getConnection(), row.subfields()));
            ps.setLong(12, row.revision());
        });
    }

    private static Array textArray(Connection conn, List<String> values) throws SQLException {
        return conn.createArrayOf("text", values.toArray(String[]::new));
    }

    private static List<String> stringList(Array array) throws SQLException {
        if (array == null) return List.of();
        return array.getArray() instanceof String[] items ? Arrays.asList(items) : List.of();
    }
}
