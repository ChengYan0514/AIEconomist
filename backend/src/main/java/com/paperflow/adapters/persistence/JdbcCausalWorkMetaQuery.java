package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalReadModelStore.WorkMeta;
import com.paperflow.causal.graph.CausalReadModelStore.WorkTopicRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;

import java.util.*;

final class JdbcCausalWorkMetaQuery {
    private final JdbcTemplate jdbc;

    JdbcCausalWorkMetaQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> listKnownSubfields() {
        Set<String> all = new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT subfield_name FROM v2_openalex_work_topic
                WHERE subfield_name IS NOT NULL AND subfield_name <> ''
                """, String.class));
        if (hasTable("work_topic")) {
            all.addAll(jdbc.queryForList("""
                    SELECT DISTINCT subfield_name FROM work_topic
                    WHERE subfield_name IS NOT NULL AND subfield_name <> ''
                    """, String.class));
        }
        return List.copyOf(all);
    }

    Map<String, WorkMeta> loadWorkMeta(Collection<String> workIds) {
        if (workIds == null || workIds.isEmpty()) return Map.of();
        String[] ids = workIds.stream().filter(Objects::nonNull).distinct().toArray(String[]::new);
        if (ids.length == 0) return Map.of();
        SqlTypeValue idParam = textArrayParam(ids);
        Map<String, WorkMeta> result = new LinkedHashMap<>();
        loadLegacyWorks(idParam, result);
        loadV2Works(idParam, result);
        loadLocalPapers(idParam, result);
        return result;
    }

    Map<String, List<WorkTopicRow>> loadWorkTopics(Collection<String> workIds) {
        if (workIds == null || workIds.isEmpty()) return Map.of();
        String[] ids = workIds.stream().filter(Objects::nonNull).distinct().toArray(String[]::new);
        if (ids.length == 0) return Map.of();
        SqlTypeValue idParam = textArrayParam(ids);
        Map<String, List<WorkTopicRow>> map = new HashMap<>();
        boolean legacy = hasTable("work_topic");
        String sql = legacy
                ? """
                  SELECT work_id, subfield_name, topic_name FROM v2_openalex_work_topic WHERE work_id = ANY(?::text[])
                  UNION
                  SELECT work_id, subfield_name, topic_name FROM work_topic WHERE work_id = ANY(?::text[])
                  ORDER BY work_id, subfield_name NULLS LAST, topic_name NULLS LAST
                  """
                : """
                  SELECT work_id, subfield_name, topic_name FROM v2_openalex_work_topic WHERE work_id = ANY(?::text[])
                  ORDER BY work_id, subfield_name NULLS LAST, topic_name NULLS LAST
                  """;
        Object[] args = legacy ? new Object[]{idParam, idParam} : new Object[]{idParam};
        jdbc.query(sql, rs -> {
            map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new WorkTopicRow(rs.getString(1), rs.getString(2), rs.getString(3)));
        }, args);
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

    private void loadLegacyWorks(SqlTypeValue idParam, Map<String, WorkMeta> result) {
        if (!hasTable("work")) return;
        jdbc.query("""
                SELECT w.work_id, w.title, w.publication_year,
                       (SELECT ws.source_id FROM work_source ws WHERE ws.work_id = w.work_id ORDER BY ws.source_id LIMIT 1),
                       (SELECT s.source_name FROM work_source ws JOIN source s ON s.source_id = ws.source_id
                        WHERE ws.work_id = w.work_id ORDER BY ws.source_id LIMIT 1)
                FROM work w WHERE w.work_id = ANY(?::text[])
                """, rs -> {
            result.put(rs.getString(1), new WorkMeta(rs.getString(1), null, rs.getString(2),
                    (Integer) rs.getObject(3), rs.getString(4), rs.getString(5)));
        }, idParam);
    }

    private void loadV2Works(SqlTypeValue idParam, Map<String, WorkMeta> result) {
        jdbc.query("""
                SELECT DISTINCT ON (v.work_id) v.work_id, v.title, v.year, v.source_id, s.source_name
                FROM v2_openalex_work v LEFT JOIN source s ON s.source_id = v.source_id
                WHERE v.work_id = ANY(?::text[]) ORDER BY v.work_id, v.source_id
                """, rs -> {
            String wid = rs.getString(1);
            WorkMeta prev = result.get(wid);
            result.put(wid, new WorkMeta(wid, prev == null ? null : prev.paperId(),
                    first(rs.getString(2), prev == null ? null : prev.title()),
                    rs.getObject(3) != null ? (Integer) rs.getObject(3) : (prev == null ? null : prev.publicationYear()),
                    first(rs.getString(4), prev == null ? null : prev.sourceId()),
                    first(rs.getString(5), prev == null ? null : prev.sourceName())));
        }, idParam);
    }

    private void loadLocalPapers(SqlTypeValue idParam, Map<String, WorkMeta> result) {
        jdbc.query("""
                SELECT matched_work_id, paper_id, title, year, source_id, source_name
                FROM v2_paper_read WHERE matched_work_id = ANY(?::text[])
                """, rs -> {
            String wid = rs.getString(1);
            WorkMeta prev = result.get(wid);
            result.put(wid, new WorkMeta(wid, rs.getString(2),
                    first(prev == null ? null : prev.title(), rs.getString(3)),
                    prev != null && prev.publicationYear() != null ? prev.publicationYear() : (Integer) rs.getObject(4),
                    first(prev == null ? null : prev.sourceId(), rs.getString(5)),
                    first(prev == null ? null : prev.sourceName(), rs.getString(6))));
        }, idParam);
    }

    private boolean hasTable(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
    }

    static SqlTypeValue textArrayParam(String[] values) {
        return (ps, idx, sqlType, typeName) -> ps.setArray(idx, ps.getConnection().createArrayOf("text", values));
    }

    private static String first(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
