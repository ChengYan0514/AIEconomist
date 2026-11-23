package com.paperflow.adapters.persistence;

import javax.sql.DataSource;
import java.sql.SQLException;

public final class DatabaseStatus {
    private final DataSource dataSource;
    private final String snapshotQuery;

    public DatabaseStatus(DataSource dataSource, String snapshotSchema) {
        this.dataSource = dataSource;
        snapshotQuery = """
                SELECT (SELECT ROW(id, display_name) FROM %1$s.sources LIMIT 1),
                       (SELECT ROW(id, title, doi, publication_year) FROM %1$s.works LIMIT 1),
                       (SELECT ROW(source_id, work_id) FROM %1$s.works_locations LIMIT 1),
                       (SELECT ROW(work_id, author_id) FROM %1$s.works_authorships LIMIT 1),
                       (SELECT ROW(id, display_name) FROM %1$s.authors LIMIT 1)
                """.formatted(snapshotSchema);
    }

    public void checkApplication() throws SQLException {
        query("SELECT paper_id FROM v2_paper LIMIT 1");
    }

    public void checkSnapshot() throws SQLException {
        query(snapshotQuery);
    }

    private void query(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var rows = statement.executeQuery(sql)) {
                rows.next();
            }
        }
    }
}
