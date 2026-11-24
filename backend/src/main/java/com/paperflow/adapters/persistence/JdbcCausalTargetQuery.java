package com.paperflow.adapters.persistence;

import com.paperflow.causal.extraction.CausalExtractionModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;

import java.util.*;

final class JdbcCausalTargetQuery {
    private final JdbcTemplate jdbc;

    JdbcCausalTargetQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TargetCandidate> resolveTargets(TargetSpec target, int limitPlusOne) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.paper_id, p.matched_work_id, p.source_id, p.title,
                       CASE WHEN p.archived THEN 'ARCHIVED'
                            WHEN p.matched_work_id IS NULL OR p.matched_work_id = '' THEN 'UNMATCHED'
                            WHEN p.status <> 'PUBLISHED' THEN 'NOT_PUBLISHED'
                            WHEN EXISTS (SELECT 1 FROM v2_causal_run ar
                                         WHERE ar.work_id = p.matched_work_id
                                           AND ar.status IN ('QUEUED','RUNNING','PUBLISHING')) THEN 'ACTIVE_EXTRACTION'
                            ELSE NULL END AS skip_reason
                FROM v2_paper_read p WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        appendTargetFilter(sql, params, target);
        sql.append(" ORDER BY p.paper_id ASC LIMIT ?");
        params.add(limitPlusOne);
        return jdbc.query(sql.toString(), (rs, i) -> new TargetCandidate(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), params.toArray());
    }

    List<BlockExportRow> loadPublishedBlocks(String paperId) {
        var rows = jdbc.query("SELECT published_parse_id, legacy, revision FROM v2_paper_read WHERE paper_id = ?",
                (rs, i) -> new Object[]{rs.getObject(1), rs.getBoolean(2), rs.getLong(3)}, paperId);
        if (rows.isEmpty()) return List.of();
        Object parseId = rows.get(0)[0];
        boolean legacy = (Boolean) rows.get(0)[1];
        long revision = (Long) rows.get(0)[2];
        if (parseId != null) {
            return jdbc.query("""
                    SELECT ('v2-' || sequence), block_type, COALESCE(body, ''),
                           CASE WHEN COALESCE(NULLIF(attributes->>'level', ''), NULLIF(attributes->>'title_level', ''),
                                              NULLIF(attributes->>'text_level', ''), NULLIF(attributes->>'titleLevel', '')) ~ '^[0-9]+$'
                                THEN COALESCE(NULLIF(attributes->>'level', ''), NULLIF(attributes->>'title_level', ''),
                                              NULLIF(attributes->>'text_level', ''), NULLIF(attributes->>'titleLevel', ''))::int
                                ELSE NULL END,
                           sequence
                    FROM v2_content_block WHERE parse_id = CAST(? AS uuid) AND block_type <> 'discarded'
                    ORDER BY sequence ASC
                    """, (rs, i) -> new BlockExportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    (Integer) rs.getObject(4), rs.getInt(5)), parseId.toString());
        }
        if (legacy && revision == 0) {
            return jdbc.query("""
                    SELECT block_id, block_type, COALESCE(block_text, ''), title_level, block_seq
                    FROM block WHERE file_id = ? AND block_type <> 'discarded' ORDER BY block_seq ASC, block_id ASC
                    """, (rs, i) -> new BlockExportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    (Integer) rs.getObject(4), rs.getInt(5)), paperId);
        }
        return List.of();
    }

    boolean isPaperEligible(String paperId, String expectedWorkId, String expectedSourceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM v2_paper_read
                              WHERE paper_id = ? AND NOT archived AND status = 'PUBLISHED'
                                AND matched_work_id = ? AND source_id = ?)
                """, Boolean.class, paperId, expectedWorkId, expectedSourceId));
    }

    private void appendTargetFilter(StringBuilder sql, List<Object> params, TargetSpec target) {
        switch (target.mode()) {
            case PAPER_IDS -> { sql.append(" AND p.paper_id = ANY(?::text[])"); params.add(textArray(target.paperIds())); }
            case WORK_IDS -> { sql.append(" AND p.matched_work_id = ANY(?::text[])"); params.add(textArray(target.workIds())); }
            case SOURCE_IDS -> { sql.append(" AND NOT p.archived AND p.source_id = ANY(?::text[])"); params.add(textArray(target.sourceIds())); }
            case FILTER -> {
                sql.append(" AND NOT p.archived");
                FilterSpec f = target.filters();
                if (f != null) {
                    if (f.q() != null && !f.q().isBlank()) {
                        sql.append(" AND (strpos(lower(p.title), lower(?)) > 0 OR strpos(lower(p.paper_id), lower(?)) > 0)");
                        params.add(f.q().strip());
                        params.add(f.q().strip());
                    }
                    if (f.sourceId() != null && !f.sourceId().isBlank()) { sql.append(" AND p.source_id = ?"); params.add(f.sourceId().strip()); }
                    if (f.yearFrom() != null) { sql.append(" AND p.year >= ?"); params.add(f.yearFrom()); }
                    if (f.yearTo() != null) { sql.append(" AND p.year <= ?"); params.add(f.yearTo()); }
                }
            }
        }
    }

    private static SqlTypeValue textArray(List<String> values) {
        String[] arr = values.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().toArray(String[]::new);
        return (ps, idx, sqlType, typeName) -> ps.setArray(idx, ps.getConnection().createArrayOf("text", arr));
    }
}
