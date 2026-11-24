package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalPayloadValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class MockCausalEngine implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;
    private final AtomicInteger counter = new AtomicInteger();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Set<String> zeroClaimWorks = ConcurrentHashMap.newKeySet();
    private final Set<String> failOnceWorks = ConcurrentHashMap.newKeySet();
    private final Set<String> failedWorks = ConcurrentHashMap.newKeySet();
    private final Set<String> holdWorks = ConcurrentHashMap.newKeySet();
    private final Map<String, JsonNode> lastRequests = new ConcurrentHashMap<>();

    MockCausalEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int submissions() { return counter.get(); }
    void zeroClaimsFor(String workId) { zeroClaimWorks.add(workId); }
    void failOnceFor(String workId) { failOnceWorks.add(workId); }
    void holdFor(String workId) { holdWorks.add(workId); }
    JsonNode lastRequest(String workId) { return lastRequests.get(workId); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v1/health".equals(path) && "GET".equals(method)) {
                respondJson(exchange, 200, Map.of("status", "ok", "profileVersion", "test-1",
                        "chatModel", "mock-chat", "embeddingModel", "mock-embed", "defaultMaxCalls", 2000));
            } else if ("/v1/runs".equals(path) && "POST".equals(method)) {
                submitRun(exchange);
            } else if (path.startsWith("/v1/runs/") && path.endsWith("/artifacts") && "GET".equals(method)) {
                String id = path.substring("/v1/runs/".length(), path.length() - "/artifacts".length());
                if (!runs.containsKey(id)) { exchange.sendResponseHeaders(404, -1); return; }
                respondJson(exchange, 200, List.of(
                        Map.of("name", "stage1_raw_responses.jsonl", "sizeBytes", 17, "sha256", "abc"),
                        Map.of("name", "result.json", "sizeBytes", 20, "sha256", "def")));
            } else if (path.startsWith("/v1/runs/") && path.contains("/artifacts/") && "GET".equals(method)) {
                byte[] bytes = "{\"status\":\"ok\"}\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else if (path.startsWith("/v1/runs/") && "DELETE".equals(method)) {
                String id = path.substring("/v1/runs/".length());
                RunRecord rec = runs.get(id);
                if (rec != null) rec.status = "CANCELLED";
                respondJson(exchange, 200, Map.of("engineRunId", id, "status", "CANCELLED"));
            } else if (path.startsWith("/v1/runs/") && "GET".equals(method)) {
                String id = path.substring("/v1/runs/".length());
                RunRecord rec = runs.get(id);
                if (rec == null) { exchange.sendResponseHeaders(404, -1); return; }
                respondJson(exchange, 200, rec.toMap());
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void submitRun(HttpExchange exchange) throws IOException {
        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        String workId = body.path("workId").asText();
        lastRequests.put(workId, body);
        String mode = body.path("ingestionMode").asText("PR_COMPAT");
        String engineRunId = "eng" + counter.incrementAndGet();
        RunRecord rec = new RunRecord(engineRunId, workId);
        if (holdWorks.contains(workId)) {
            rec.status = "RUNNING";
            rec.stage = "STAGE1";
        } else if (failOnceWorks.contains(workId) && failedWorks.add(workId)) {
            rec.status = "FAILED";
            rec.stage = "STAGE1";
            rec.errorCode = "MODEL_TIMEOUT";
            rec.errorMessage = "Simulated timeout";
        } else {
            rec.status = "SUCCEEDED";
            rec.stage = "DONE";
            rec.result = buildResult(workId, mode);
        }
        runs.put(engineRunId, rec);
        respondJson(exchange, 202, Map.of("engineRunId", engineRunId));
    }

    private Map<String, Object> buildResult(String workId, String mode) {
        if (zeroClaimWorks.contains(workId)) {
            return Map.of("workId", workId, "rows", List.of(), "claimCount", 0, "collisionCount", 0,
                    "calls", 2, "tokens", 300, "modelVersions", List.of("mock-chat"));
        }
        String causeStd = "STANDARDIZED".equals(mode) ? "Minimum Wage" : "minimum wage hike";
        String effectStd = "STANDARDIZED".equals(mode) ? "Employment" : "teen employment";
        String hash = CausalPayloadValidator.claimHash(causeStd, effectStd);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paper_id", workId);
        row.put("claim_cause", causeStd);
        row.put("claim_effect", effectStd);
        row.put("claim_hash", hash);
        row.put("claim", "Minimum wage hike reduces teen employment");
        row.put("cause", "minimum wage hike");
        row.put("effect", "teen employment");
        row.put("sign_of_impact", "negative");
        row.put("type_of_relationship", "causal");
        row.put("evidence", "Section 4 Table 2 estimates -0.08 elasticity.");
        row.put("causal_inference_method", "DID");
        row.put("is_main_contribution", true);
        row.put("statistical_significance", "p < 0.05");
        row.put("cause_score", 0.945);
        row.put("effect_score", 0.912);
        return Map.of("workId", workId, "rows", List.of(row), "claimCount", 1, "collisionCount", 0,
                "calls", 5, "tokens", 1200, "modelVersions", List.of("mock-chat", "mock-embed"));
    }

    private void respondJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = json.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class RunRecord {
        final String engineRunId;
        final String workId;
        volatile String status = "RUNNING";
        volatile String stage = "PREPARE";
        volatile Map<String, Object> result;
        volatile String errorCode;
        volatile String errorMessage;

        RunRecord(String engineRunId, String workId) {
            this.engineRunId = engineRunId;
            this.workId = workId;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("engineRunId", engineRunId);
            map.put("status", status);
            map.put("stage", stage);
            map.put("usage", Map.of("calls", result == null ? 1 : result.get("calls"),
                    "tokens", result == null ? 100 : result.get("tokens"),
                    "claimCount", result == null ? 0 : result.get("claimCount"),
                    "collisionCount", 0, "modelVersions", List.of("mock-chat")));
            if (result != null) map.put("result", result);
            if (errorCode != null) map.put("errorCode", errorCode);
            if (errorMessage != null) map.put("errorMessage", errorMessage);
            return map;
        }
    }
}
