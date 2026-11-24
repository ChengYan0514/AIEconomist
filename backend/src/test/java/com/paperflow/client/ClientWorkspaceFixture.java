package com.paperflow.client;

import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.UUID;

final class ClientWorkspaceFixture {
    static final String PASSWORD = "workspace-test-password";
    static final UUID INTERRUPTED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ClientWorkspaceFixture() {}

    static void configure(DynamicPropertyRegistry properties) {
        var url = System.getenv("PAPERFLOW_TEST_JDBC_URL");
        var user = System.getenv().getOrDefault("PAPERFLOW_TEST_DB_USER", "paperflow");
        var password = System.getenv().getOrDefault("PAPERFLOW_TEST_DB_PASSWORD", "");
        var schema = "pf_client_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var snapshot = schema + "_snapshot";
        var jdbcUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE SCHEMA " + snapshot);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("legacy.sql"));
            statement.execute("SET search_path TO " + snapshot);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("snapshot.sql"));
            seedInterruptedTask(jdbcUrl, user, password);
            var storage = Files.createTempDirectory("paperflow-client-test-");
            properties.add("spring.datasource.url", () -> jdbcUrl);
            properties.add("spring.datasource.username", () -> user);
            properties.add("spring.datasource.password", () -> password);
            properties.add("paperflow.openalex-schema", () -> snapshot);
            properties.add("paperflow.storage", () -> storage.resolve("v2"));
            properties.add("paperflow.legacy-storage", () -> storage.resolve("legacy"));
            properties.add("paperflow.client.citations.provider", () -> "stub");
            properties.add("paperflow.client.citations.max-active-per-user", () -> 1);
            properties.add("paperflow.job-delay", () -> 30000);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare client workspace", error);
        }
    }

    private static void seedInterruptedTask(String url, String user, String password) throws Exception {
        Flyway.configure().dataSource(url, user, password).table("v2_flyway_schema_history")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password);
             var account = connection.prepareStatement("""
                     INSERT INTO v2_admin (username, display_name, password_hash, role)
                     VALUES ('restart-user', 'Restart user', ?, 'RESEARCHER')
                     """);
             var task = connection.prepareStatement("""
                     INSERT INTO v2_client_citation_task (task_id, username, title, status, stage, progress,
                                                         original_text, citation_style, engine_provider)
                     VALUES (?, 'restart-user', 'Old task', 'RUNNING', 'RANK_EVIDENCE', 0.75,
                             'Old task.', 'apa', 'stub')
                     """)) {
            account.setString(1, new BCryptPasswordEncoder(4).encode(PASSWORD));
            account.executeUpdate();
            task.setObject(1, INTERRUPTED);
            task.executeUpdate();
        }
    }

    static void seedBibliography(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO work (work_id, title) VALUES ('W-local', 'Local alternative title')");
        jdbc.update("""
                INSERT INTO work_author (work_id, author_id, author_name)
                VALUES ('W-local', 'A2', 'John Local'), ('W-local', 'A1', 'Jane Local')
                """);
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, title, matched_work_id,
                                      doi, landing_url, file_revision, original_type)
                VALUES ('citation-local', true, 'S124', 'Paper fallback title', 'W-local',
                        'HTTP://DX.DOI.ORG/10.2307/1884513', 'https://example.org/paper', 0, 'PDF')
                """);
    }
}
