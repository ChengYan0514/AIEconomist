package com.paperflow.client.adapters.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.citation.CitationEngine.CancellationSignal;
import com.paperflow.client.citation.CitationEngine.EngineFailure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CitationEngineHttp {
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();
    private final String baseUrl;
    private final Duration timeout;
    private final Duration interval;
    private final ObjectMapper json;

    CitationEngineHttp(URI url, Duration timeout, Duration interval, ObjectMapper json) {
        this.baseUrl = url.toString().replaceFirst("/+$", "");
        this.timeout = timeout;
        this.interval = interval;
        this.json = json;
    }

    boolean healthy(String path) {
        try {
            return exchange("GET", path, null, HEALTH_TIMEOUT, 200).path("status").asText().equals("ok");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (EngineFailure failure) {
            return false;
        }
    }

    Run run(CancellationSignal cancelled) {
        return new Run(System.nanoTime() + timeout.toNanos(), cancelled);
    }

    String runPath(String root, JsonNode reply, String field) {
        var id = reply.path(field).asText("");
        if (id.isBlank() || id.length() > 128 || id.equals(".") || id.equals("..")) {
            throw new EngineFailure("CITATION_ENGINE_FAILED", "引擎未返回有效的运行标识");
        }
        return root + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    void cancel(String path) {
        boolean interrupted = Thread.interrupted();
        try {
            exchange("DELETE", path, null, HEALTH_TIMEOUT, 200, 204);
        } catch (InterruptedException error) {
            interrupted = true;
        } catch (EngineFailure ignored) {
            // The local task is already cancelled.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private JsonNode exchange(String method, String path, Object body, Duration limit, int... accepted)
            throws InterruptedException {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(limit)
                    .header("Accept", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (body != null) request.header("Content-Type", "application/json");
            var response = send(request.build(), limit);
            for (int status : accepted) {
                if (status == response.statusCode()) {
                    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
                }
            }
            throw new EngineFailure("CITATION_ENGINE_FAILED", "引擎请求失败（HTTP " + response.statusCode() + "）");
        } catch (IOException | IllegalArgumentException error) {
            throw new EngineFailure("CITATION_ENGINE_FAILED", "无法读取引擎响应");
        }
    }

    private HttpResponse<String> send(HttpRequest request, Duration limit) throws InterruptedException {
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return response.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            response.cancel(true);
            throw timedOut();
        } catch (InterruptedException error) {
            response.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException) throw timedOut();
            throw new EngineFailure("CITATION_ENGINE_FAILED", "无法读取引擎响应");
        }
    }

    private EngineFailure timedOut() {
        return new EngineFailure("CITATION_ENGINE_TIMEOUT", "引擎运行超时");
    }

    final class Run {
        private final long deadline;
        private final CancellationSignal cancelled;

        private Run(long deadline, CancellationSignal cancelled) {
            this.deadline = deadline;
            this.cancelled = cancelled;
        }

        JsonNode exchange(String method, String path, Object body, int status) throws InterruptedException {
            cancelled.check();
            return CitationEngineHttp.this.exchange(method, path, body, Duration.ofNanos(remaining()), status);
        }

        void pause() throws InterruptedException {
            long pauseUntil = System.nanoTime() + Math.min(interval.toNanos(), remaining());
            long nanos;
            while ((nanos = pauseUntil - System.nanoTime()) > 0) {
                cancelled.check();
                TimeUnit.NANOSECONDS.sleep(Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(100)));
            }
            cancelled.check();
        }

        private long remaining() {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw timedOut();
            return remaining;
        }
    }
}
