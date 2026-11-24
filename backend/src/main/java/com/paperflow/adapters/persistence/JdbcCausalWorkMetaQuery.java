package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalReadModelStore.WorkMeta;
import com.paperflow.causal.graph.CausalReadModelStore.WorkTopicRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;

import java.util.*;

final class JdbcCausalWorkMetaQuery {
    private final JdbcTemplate jdbc;
    private final String openalexSchema;

    JdbcCausalWorkMetaQuery(JdbcTemplate jdbc, String openalexSchema) {
        this.jdbc = jdbc;
        this.openalexSchema = openalexSchema;
    }

    Map<String, WorkMeta> loadWorkMeta(Collection<String> workIds) {
        if (workIds == null || workIds.isEmpty()) return Map.of();
        String[] ids = workIds.stream().filter(Objects::nonNull).distinct().toArray(String[]::new);
        if (ids.length == 0) return Map.of();
        SqlTypeValue idParam = textArrayParam(ids);
        Map<String, WorkMeta> result = new LinkedHashMap<>();
        loadWorks(idParam, result);
        loadLocalPapers(idParam, result);
        return result;
    }

    Map<String, List<WorkTopicRow>> loadWorkTopics(Collection<String> workIds) {
        if (workIds == null || workIds.isEmpty()) return Map.of();
        String[] ids = workIds.stream().filter(Objects::nonNull).distinct().toArray(String[]::new);
        if (ids.length == 0) return Map.of();
        SqlTypeValue idParam = textArrayParam(ids);
        Map<String, List<WorkTopicRow>> map = new HashMap<>();
        jdbc.query("""
                SELECT work_id, subfield_name, topic_name FROM work_topic
                WHERE work_id = ANY(?::text[])
                ORDER BY work_id, subfield_name NULLS LAST, topic_name NULLS LAST
                """, rs -> {
            map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new WorkTopicRow(rs.getString(1), rs.getString(2), rs.getString(3)));
        }, idParam);
        return map;
    }

    List<WorkMeta> searchWorkMeta(String query, Collection<String> candidateWorkIds, int limit) {
        String lower = query.toLowerCase(Locale.ROOT);
        Map<String, WorkMeta> all = loadWorkMeta(candidateWorkIds);
        return candidateWorkIds.stream()
                .map(id -> all.getOrDefault(id, new WorkMeta(id, null, null, null, null, null)))
                .filter(m -> m.workId().toLowerCase(Locale.ROOT).contains(lower)
                        || (m.title() != null && m.title().toLowerCase(Locale.ROOT).contains(lower)))
                .limit(Math.max(limit * 3L, 50L))
                .toList();
    }

    private void loadWorks(SqlTypeValue idParam, Map<String, WorkMeta> result) {
        String sql = """
                SELECT w.work_id, w.title, w.publication_year,
                       (SELECT ws.source_id FROM work_source ws WHERE ws.work_id = w.work_id ORDER BY ws.source_id LIMIT 1),
                       (SELECT os.display_name FROM work_source ws LEFT JOIN %1$s.sources os ON os.id = ws.source_id
                        WHERE ws.work_id = w.work_id ORDER BY ws.source_id LIMIT 1)
                FROM work w WHERE w.work_id = ANY(?::text[])
                """.formatted(openalexSchema);
        jdbc.query(sql, rs -> {
            result.put(rs.getString(1), new WorkMeta(rs.getString(1), null, rs.getString(2),
                    (Integer) rs.getObject(3), rs.getString(4), rs.getString(5)));
        }, idParam);
    }

    private void loadLocalPapers(SqlTypeValue idParam, Map<String, WorkMeta> result) {
        String sql = """
                SELECT p.matched_work_id, p.paper_id, p.title, p.year, p.source_id, os.display_name
                FROM v2_paper_read p
                LEFT JOIN %1$s.sources os ON os.id = p.source_id
                WHERE p.matched_work_id = ANY(?::text[])
                """.formatted(openalexSchema);
        jdbc.query(sql, rs -> {
            String wid = rs.getString(1);
            WorkMeta prev = result.get(wid);
            result.put(wid, new WorkMeta(wid, rs.getString(2),
                    first(prev == null ? null : prev.title(), rs.getString(3)),
                    prev != null && prev.publicationYear() != null ? prev.publicationYear() : (Integer) rs.getObject(4),
                    first(prev == null ? null : prev.sourceId(), rs.getString(5)),
                    first(prev == null ? null : prev.sourceName(), rs.getString(6))));
        }, idParam);
    }

    static SqlTypeValue textArrayParam(String[] values) {
        return (ps, idx, sqlType, typeName) -> ps.setArray(idx, ps.getConnection().createArrayOf("text", values));
    }

    private static String first(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
