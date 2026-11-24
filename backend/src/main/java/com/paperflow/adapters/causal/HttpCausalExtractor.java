package com.paperflow.adapters.causal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.extraction.CausalExtractor;
import com.paperflow.causal.model.CausalNotFound;
import com.paperflow.causal.model.IngestionMode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class HttpCausalExtractor implements CausalExtractor {
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI base;
    private final ObjectMapper json;
    private final Duration timeout;

    public HttpCausalExtractor(URI base, ObjectMapper json, Duration timeout) {
        this.base = URI.create(base.toString().replaceFirst("/+$", "") + "/");
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public EngineHealth health() {
        try {
            var request = HttpRequest.newBuilder(base.resolve("v1/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new EngineHealth(false, "unknown", "", "", 2000, "健康检查返回 HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            return new EngineHealth("ok".equalsIgnoreCase(body.path("status").asText()),
                    body.path("profileVersion").asText("1"),
                    body.path("chatModel").asText(""),
                    body.path("embeddingModel").asText(""),
                    body.path("defaultMaxCalls").asInt(2000),
                    "因果抽取引擎正常");
        } catch (Exception error) {
            return new EngineHealth(false, "unknown", "", "", 2000, "无法连接因果抽取引擎");
        }
    }

    public void checkHealth() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve("v1/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("因果抽取引擎健康检查失败: HTTP " + response.statusCode());
        }
    }

    @Override
    public String submit(
            UUID runId, String workId, IngestionMode mode, int maxCalls, String resumeEngineRunId,
            List<BlockExportRow> blocks
    ) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId.toString());
        body.put("workId", workId);
        body.put("ingestionMode", mode.engineMode());
        body.put("maxCalls", maxCalls);
        if (resumeEngineRunId != null && !resumeEngineRunId.isBlank()) body.put("resumeEngineRunId", resumeEngineRunId);
        body.put("blocks", blocks);
        var request = HttpRequest.newBuilder(base.resolve("v1/runs"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        JsonNode response = sendJson(request);
        String engineRunId = response.path("engineRunId").asText("");
        if (engineRunId.isBlank()) throw new IOException("因果抽取引擎未返回 engineRunId");
        return engineRunId;
    }

    @Override
    public EngineRunStatus poll(String engineRunId) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve("v1/runs/" + encode(engineRunId)))
                .timeout(Duration.ofSeconds(15)).GET().build();
        JsonNode node = sendJson(request);
        JsonNode usage = node.path("usage");
        List<String> models = new ArrayList<>();
        usage.path("modelVersions").forEach(m -> { if (m.isTextual()) models.add(m.asText()); });
        RunMetrics metrics = new RunMetrics(
                usage.path("claimCount").asInt(node.path("result").path("claimCount").asInt(0)),
                usage.path("collisionCount").asInt(node.path("result").path("collisionCount").asInt(0)),
                usage.path("calls").asInt(0),
                usage.path("tokens").asInt(0),
                List.copyOf(models));
        String resultJson = node.hasNonNull("result") ? json.writeValueAsString(node.get("result")) : null;
        return new EngineRunStatus(engineRunId, node.path("status").asText("RUNNING"),
                node.path("stage").asText("PREPARE"), metrics, resultJson,
                node.path("errorCode").asText(null), node.path("errorMessage").asText(null));
    }

    @Override
    public void cancel(String engineRunId) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve("v1/runs/" + encode(engineRunId)))
                .timeout(Duration.ofSeconds(10)).DELETE().build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Override
    public List<ArtifactInfo> artifacts(String engineRunId) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve("v1/runs/" + encode(engineRunId) + "/artifacts"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        JsonNode root = sendJson(request);
        List<ArtifactInfo> list = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(item -> list.add(new ArtifactInfo(
                    item.path("name").asText(), item.path("sizeBytes").asLong(0), item.path("sha256").asText(""))));
        }
        return list;
    }

    @Override
    public InputStream openArtifact(String engineRunId, String name) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve("v1/runs/" + encode(engineRunId) + "/artifacts/" + encode(name)))
                .timeout(timeout).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 404) {
            response.body().close();
            throw new CausalNotFound("ARTIFACT_NOT_FOUND", "产物文件不存在或已过期清理");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("下载产物失败: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("因果引擎请求失败: HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
