package com.paperflow.client.adapters.persistence;

import com.paperflow.client.citation.LocalMetadataEnricher;
import com.paperflow.client.citation.model.CitationIdentity;
import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JdbcLocalMetadataEnricher implements LocalMetadataEnricher {
    private static final String DOI_SQL = "nullif(regexp_replace(lower(btrim(%s.doi)), "
            + "'^(https?://(dx\\.)?doi\\.org/|doi:)[[:space:]]*', ''), '')";
    private final JdbcTemplate jdbc;
    private final String workSql;
    private final String paperSql;

    public JdbcLocalMetadataEnricher(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        workSql = workSql(schema);
        paperSql = paperSql(schema);
    }

    @Override
    public CitationResult enrich(CitationResult result) {
        var ids = new LinkedHashSet<String>();
        var dois = new LinkedHashSet<String>();
        for (var references : result.referencesByStatement().values()) {
            for (var ref : references) {
                add(ids, CitationIdentity.workId(ref.workId()));
                add(dois, CitationIdentity.doi(ref.doi()));
            }
        }
        if (ids.isEmpty() && dois.isEmpty()) return result;
        var works = query(workSql, ids, dois);
        ids.addAll(works.byWork.keySet());
        dois.addAll(works.byDoi.keySet());
        var papers = query(paperSql, ids, dois);
        var linkedWorkIds = new LinkedHashSet<>(papers.byWork.keySet());
        linkedWorkIds.removeAll(ids);
        if (!linkedWorkIds.isEmpty()) works.include(query(workSql, linkedWorkIds, Set.of()));
        var enriched = new LinkedHashMap<String, List<CitationReference>>();
        result.referencesByStatement().forEach((id, references) -> enriched.put(id,
                references.stream().map(reference -> merge(reference, works, papers)).toList()));
        return result.withReferences(enriched);
    }

    private CitationReference merge(CitationReference ref, MetadataIndex works, MetadataIndex papers) {
        var id = CitationIdentity.workId(ref.workId());
        var doi = CitationIdentity.doi(ref.doi());
        var work = works.find(id, doi);
        var paper = work == null ? papers.find(id, doi) : papers.find(work.workId(), work.doi());
        if (paper == null) paper = papers.find(id, doi);
        if (work == null && paper != null) work = works.find(paper.workId(), paper.doi());
        if (work != null) return work.enrich(ref, paper);
        return paper == null ? ref : paper.enrich(ref, null);
    }

    private MetadataIndex query(String sql, Set<String> ids, Set<String> dois) {
        var index = new MetadataIndex();
        jdbc.query(sql, rs -> {
            var row = new LocalCitationMetadata(rs.getString("work_id"), rs.getString("paper_id"), rs.getString("doi"),
                    rs.getString("title"), authors(rs.getArray("authors")), rs.getObject("publication_year", Integer.class),
                    rs.getString("venue"), rs.getString("source_id"), rs.getString("url"));
            if (row.workId() != null) index.byWork.putIfAbsent(row.workId(), row);
            if (row.doi() != null) index.byDoi.putIfAbsent(row.doi(), row);
        }, array(ids), array(dois));
        return index;
    }

    private String workSql(String schema) {
        return """
                SELECT w.work_id, null AS paper_id, %1$s AS doi, w.title, w.publication_year,
                       array(SELECT btrim(a.author_name) FROM work_author a
                             WHERE a.work_id = w.work_id AND nullif(btrim(a.author_name), '') IS NOT NULL
                             ORDER BY (to_jsonb(a)->>'author_position')::int NULLS LAST, a.author_id) AS authors,
                       source.source_id, source.venue, null AS url
                FROM work w
                LEFT JOIN LATERAL (
                    SELECT ws.source_id, nullif(btrim(s.display_name), '') AS venue
                    FROM work_source ws LEFT JOIN %2$s.sources s ON s.id = ws.source_id
                    WHERE ws.work_id = w.work_id
                    ORDER BY venue NULLS LAST, ws.source_id LIMIT 1
                ) source ON true
                WHERE w.work_id = ANY(?::text[]) OR %1$s = ANY(?::text[])
                ORDER BY w.work_id
                """.formatted(DOI_SQL.formatted("w"), schema);
    }

    private String paperSql(String schema) {
        return """
                SELECT p.matched_work_id AS work_id, p.paper_id, %1$s AS doi, p.title, p.publication_year,
                       string_to_array(p.authors, ';') AS authors, p.source_id, s.display_name AS venue, p.landing_url AS url
                FROM v2_paper p LEFT JOIN %2$s.sources s ON s.id = p.source_id
                WHERE p.matched_work_id = ANY(?::text[]) OR %1$s = ANY(?::text[])
                ORDER BY (p.archived_at IS NOT NULL), p.paper_id
                """.formatted(DOI_SQL.formatted("p"), schema);
    }

    private static List<String> authors(Array values) throws SQLException {
        if (values == null) return List.of();
        try {
            return Arrays.stream((String[]) values.getArray()).filter(value -> value != null && !value.isBlank())
                    .map(String::strip).toList();
        } finally {
            values.free();
        }
    }

    private static SqlTypeValue array(Set<String> values) {
        return (statement, index, type, name) ->
                statement.setArray(index, statement.getConnection().createArrayOf("text", values.toArray(String[]::new)));
    }

    private static void add(Set<String> values, String value) {
        if (value != null) values.add(value);
    }

    private static final class MetadataIndex {
        private final Map<String, LocalCitationMetadata> byWork = new HashMap<>();
        private final Map<String, LocalCitationMetadata> byDoi = new HashMap<>();

        private void include(MetadataIndex other) {
            other.byWork.forEach(byWork::putIfAbsent);
            other.byDoi.forEach(byDoi::putIfAbsent);
        }

        private LocalCitationMetadata find(String id, String doi) {
            var byId = byWork.get(id);
            return byId == null ? byDoi.get(doi) : byId;
        }
    }
}
