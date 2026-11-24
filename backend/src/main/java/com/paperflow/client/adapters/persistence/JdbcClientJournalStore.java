package com.paperflow.client.adapters.persistence;

import com.paperflow.client.catalog.ClientJournalStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

public final class JdbcClientJournalStore implements ClientJournalStore {
    private final JdbcTemplate jdbc;
    private final String sql;

    public JdbcClientJournalStore(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.sql = """
                SELECT p.source_id, coalesce(nullif(btrim(s.display_name), ''), p.source_id) AS name,
                       count(*) AS papers, count(*) FILTER (WHERE p.status = 'PUBLISHED') AS published,
                       min(p.year) AS year_min, max(p.year) AS year_max, count(*) OVER () AS total
                FROM v2_paper_read p
                LEFT JOIN %s.sources s ON s.id = p.source_id
                WHERE NOT p.archived AND p.source_id IS NOT NULL AND btrim(p.source_id) <> ''
                  AND (strpos(lower(coalesce(s.display_name, '')), lower(?)) > 0
                       OR strpos(lower(p.source_id), lower(?)) > 0)
                GROUP BY p.source_id, s.display_name
                ORDER BY published DESC, papers DESC, name, p.source_id
                LIMIT ?
                """.formatted(schema);
    }

    @Override
    public Catalog search(String query, int limit) {
        return jdbc.query(sql, rs -> {
            long total = 0;
            var items = new ArrayList<Journal>();
            while (rs.next()) {
                total = rs.getLong("total");
                items.add(new Journal(rs.getString("source_id"), rs.getString("name"), rs.getLong("papers"),
                        rs.getLong("published"), rs.getObject("year_min", Integer.class), rs.getObject("year_max", Integer.class)));
            }
            return new Catalog(List.copyOf(items), total);
        }, query, query, limit);
    }
}
