package com.paperflow.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceConnectivityProber {
    public record ProbeResult(boolean ok, long latencyMs, String message, String detail) {}

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json;

    public ServiceConnectivityProber(ObjectMapper json) {
        this.json = json;
    }

    public ProbeResult testMineru(String rawUrl) {
        URI base = ServiceSettings.parseHttpUri(rawUrl, "MinerU 服务地址");
        return probeHealth(base, "MinerU 服务");
    }

    public ProbeResult testCausalEngine(String rawUrl) {
        URI base = ServiceSettings.parseHttpUri(rawUrl, "因果抽取引擎地址");
        long started = System.nanoTime();
        try {
            var req = HttpRequest.newBuilder(URI.create(base + "/v1/health")).timeout(Duration.ofSeconds(5)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = elapsedMs(started);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new ProbeResult(false, ms, "因果抽取引擎返回 HTTP " + resp.statusCode(), snippet(resp.body()));
            }
            JsonNode body = json.readTree(resp.body());
            String version = body.path("profileVersion").asText("1");
            return new ProbeResult(true, ms, "因果抽取引擎连通正常（Profile v" + version + "）", "");
        } catch (Exception error) {
            return new ProbeResult(false, elapsedMs(started), "无法连接因果抽取引擎", conciseError(error));
        }
    }

    public ProbeResult testChatModel(
            String rawBaseUrl, String model, String apiKey, boolean enableThinking, ThinkingStyle style
    ) {
        URI base = ServiceSettings.parseHttpUri(rawBaseUrl, "对话模型 API 地址");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("请填写对话模型名称");
        ThinkingStyle resolvedStyle = (style == null ? ThinkingStyle.AUTO : style).effectiveStyle(base.toString(), model);
        Map<String, Object> extra = (style == null ? ThinkingStyle.AUTO : style)
                .resolveExtraBody(base.toString(), model.strip(), enableThinking);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.strip());
        payload.put("messages", List.of(Map.of("role", "user", "content", "Reply with OK.")));
        payload.put("max_tokens", 16);
        payload.put("temperature", 0);
        payload = ThinkingStyle.mergeIntoBody(payload, extra);
        return probeOpenAiEndpoint(URI.create(base + "/chat/completions"), apiKey, payload,
                "对话模型连通成功（思考协议：" + resolvedStyle.name() + "，" + (enableThinking ? "开启思考" : "关闭思考") + "）",
                body -> {
                    JsonNode msg = body.path("choices").path(0).path("message");
                    String reply = msg.path("content").asText("");
                    String actualModel = body.path("model").asText(model.strip());
                    return "返回模型：" + actualModel + (reply.isBlank() ? "" : " · 示例回复：" + snippet(reply));
                });
    }

    public ProbeResult testEmbeddingModel(String rawBaseUrl, String model, String apiKey) {
        URI base = ServiceSettings.parseHttpUri(rawBaseUrl, "嵌入模型 API 地址");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("请填写嵌入模型名称");
        Map<String, Object> payload = Map.of("model", model.strip(), "input", "economics");
        return probeOpenAiEndpoint(URI.create(base + "/embeddings"), apiKey, payload, "嵌入模型连通成功", body -> {
            JsonNode vec = body.path("data").path(0).path("embedding");
            int dims = vec.isArray() ? vec.size() : 0;
            if (dims <= 0) throw new IllegalStateException("响应中未包含有效向量数组");
            return "向量维度：" + dims + " · 返回模型：" + body.path("model").asText(model.strip());
        });
    }

    private ProbeResult probeHealth(URI base, String label) {
        long started = System.nanoTime();
        try {
            var req = HttpRequest.newBuilder(URI.create(base + "/v1/health")).timeout(Duration.ofSeconds(5)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = elapsedMs(started);
            if (resp.statusCode() / 100 != 2) {
                return new ProbeResult(false, ms, label + "健康检查返回 HTTP " + resp.statusCode(), snippet(resp.body()));
            }
            return new ProbeResult(true, ms, label + "健康接口响应正常", "");
        } catch (Exception error) {
            return new ProbeResult(false, elapsedMs(started), "无法连接" + label, conciseError(error));
        }
    }

    private ProbeResult probeOpenAiEndpoint(
            URI endpoint, String apiKey, Map<String, Object> payload, String okMessage,
            java.util.function.Function<JsonNode, String> detailReader
    ) {
        long started = System.nanoTime();
        try {
            var builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.strip());
            }
            var req = builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = elapsedMs(started);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new ProbeResult(false, ms, "模型接口返回 HTTP " + resp.statusCode(), extractErrorDetail(resp.body()));
            }
            String detail = detailReader.apply(json.readTree(resp.body()));
            return new ProbeResult(true, ms, okMessage, detail);
        } catch (Exception error) {
            return new ProbeResult(false, elapsedMs(started), "模型接口调用失败", conciseError(error));
        }
    }

    private String extractErrorDetail(String rawBody) {
        try {
            JsonNode root = json.readTree(rawBody);
            String msg = root.path("error").path("message").asText("");
            if (!msg.isBlank()) return snippet(msg);
        } catch (Exception ignored) {}
        return snippet(rawBody);
    }

    private static long elapsedMs(long startedNano) {
        return Math.max(1L, (System.nanoTime() - startedNano) / 1_000_000L);
    }

    private static String snippet(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").strip();
        return clean.length() > 240 ? clean.substring(0, 240) + "…" : clean;
    }

    private static String conciseError(Exception error) {
        String msg = error.getMessage();
        return msg == null || msg.isBlank() ? error.getClass().getSimpleName() : snippet(msg);
    }
}
