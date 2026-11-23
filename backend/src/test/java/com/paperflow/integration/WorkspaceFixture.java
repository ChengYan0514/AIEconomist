package com.paperflow.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class WorkspaceFixture implements AutoCloseable {
    static final String PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n";
    private final String url = System.getenv("PAPERFLOW_TEST_JDBC_URL");
    private final String user = System.getenv().getOrDefault("PAPERFLOW_TEST_DB_USER", "paperflow");
    private final String password = System.getenv().getOrDefault("PAPERFLOW_TEST_DB_PASSWORD", "");
    private final String schema = "pf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final ExecutorService mineruRequests = Executors.newCachedThreadPool();
    private final HttpServer mineru;
    private final Path directory;
    private final AtomicInteger task = new AtomicInteger();
    private final AtomicInteger uploads = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile long healthDelayMillis;

    WorkspaceFixture() {
        try {
            setupDatabase();
            directory = Files.createTempDirectory("paperflow-integration-");
            Files.createDirectories(legacy().resolve("original"));
            Files.writeString(legacy().resolve("original/legacy.pdf"), PDF);
            mineru = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mineru.setExecutor(mineruRequests);
            mineru.createContext("/v1/", this::mineruResponse);
            mineru.start();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare isolated Paperflow fixture", error);
        }
    }

    static WorkspaceFixture configure(DynamicPropertyRegistry properties) {
        var fixture = new WorkspaceFixture();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", fixture::user);
        properties.add("spring.datasource.password", fixture::password);
        properties.add("paperflow.openalex-schema", fixture::snapshot);
        properties.add("paperflow.storage", fixture::storage);
        properties.add("paperflow.legacy-storage", fixture::legacy);
        properties.add("paperflow.mineru-url", fixture::mineru);
        properties.add("paperflow.mineru-timeout", () -> "PT5S");
        properties.add("paperflow.admin-username", () -> "integration");
        properties.add("paperflow.admin-password", () -> "workspace-test-password");
        properties.add("paperflow.job-delay", () -> 30);
        return fixture;
    }

    private void setupDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE SCHEMA " + snapshot());
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("legacy.sql"));
            statement.execute("SET search_path TO " + snapshot());
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("snapshot.sql"));
        }
    }

    void seedVersionTwoAccount(String username, String oldPassword, UUID jobId) {
        Flyway.configure().dataSource(jdbcUrl(), user(), password()).table("v2_flyway_schema_history")
                .baselineOnMigrate(true).baselineVersion("0").target("2").load().migrate();
        try (var connection = DriverManager.getConnection(jdbcUrl(), user(), password());
             var account = connection.prepareStatement("INSERT INTO v2_admin (username, password_hash) VALUES (?, ?)");
             var job = connection.prepareStatement("INSERT INTO v2_job (job_id, kind, username) VALUES (?, 'PARSE', ?)")) {
            account.setString(1, username);
            account.setString(2, new BCryptPasswordEncoder(4).encode(oldPassword));
            account.executeUpdate();
            job.setObject(1, jobId);
            job.setString(2, username);
            job.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot seed a v2 account and its job", error);
        }
    }

    String jdbcUrl() { return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema; }
    String user() { return user; }
    String password() { return password; }
    String snapshot() { return schema + "_snapshot"; }
    String mineru() { return "http://127.0.0.1:" + mineru.getAddress().getPort(); }
    Path legacy() { return directory.resolve("legacy"); }
    Path storage() { return directory.resolve("v2"); }

    static List<String> legacyContents(JdbcTemplate jdbc) {
        return List.of("original_file", "original_file_job", "text_file", "block", "block_image", "block_table",
                        "block_equation", "block_footnote", "block_reference", "source", "work", "work_source",
                        "work_author", "claim_table", "paper_claim_table", "openalex_source_search", "causal_publication")
                .stream().map(table -> jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "
                        + table + " t", String.class)).toList();
    }

    int mineruSubmissions() { return task.get(); }
    int mineruUploads() { return uploads.get(); }
    void delayMineruHealth(long millis) { healthDelayMillis = millis; }

    private void mineruResponse(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/health") && exchange.getRequestMethod().equals("GET")) {
                try {
                    TimeUnit.MILLISECONDS.sleep(healthDelayMillis);
                    respond(exchange, "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else if (path.equals("/v1/tasks") && exchange.getRequestMethod().equals("POST")) {
                createTask(exchange);
            } else if (path.contains("/uploads/") && exchange.getRequestMethod().equals("PUT")) {
                acceptUpload(exchange);
            } else if (path.endsWith("/content")) {
                respond(exchange, zip("structured_content.json", """
                        {"pages":[{"page_idx":0,"blocks":[{"type":"text","content":"Parsed body"}]},
                        {"page_idx":3,"blocks":[{"type":"paragraph_title","level":2,"content":"Results"},
                        {"type":"table","bbox":[10,20,30,40],"content":"<table></table>",
                        "captions":[{"content":"Table caption"}],"image_source":"images/result.png"}]}]}
                        """, "markdown.md", "Parsed body", "images/result.png", "fixture image"));
            } else if (reads.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                String id = path.substring(path.lastIndexOf('/') + 1);
                respond(exchange, ("""
                        {"task_id":"%s","status":"completed","files":[{"file_id":"file-%s","status":"completed",
                        "result_url":"/v1/files/file-%s/content"}]}
                        """.formatted(id, id, id)).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void createTask(HttpExchange exchange) throws IOException {
        String manifest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!manifest.contains("\"file_name\":") || !manifest.contains("\"bytes\":" + PDF.length())) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String id = "fixture-" + task.incrementAndGet();
        respond(exchange, ("""
                {"task_id":"%s","status":"waiting-file","files":[{"file_id":"file-%s","status":"waiting-file",
                "upload":{"url":"/v1/tasks/%s/uploads/file-%s","headers":{"Content-Type":"application/octet-stream"}}}]}
                """.formatted(id, id, id, id)).getBytes(StandardCharsets.UTF_8));
    }

    private void acceptUpload(HttpExchange exchange) throws IOException {
        if (!"application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type")) ||
                !PDF.equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        uploads.incrementAndGet();
        exchange.sendResponseHeaders(200, -1);
    }

    static byte[] zip(String... entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private void respond(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        mineru.stop(0);
        mineruRequests.shutdownNow();
    }
}
