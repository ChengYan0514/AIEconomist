package com.paperflow.client.adapters.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paperflow.client.citation.CitationEngine;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationUpdate;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static com.paperflow.client.citation.model.CitationUpdate.EventType.*;

public final class StandardHttpCitationEngine implements CitationEngine {
    private static final String RUNS = "/v1/citation-runs";
    private final CitationEngineHttp http;
    private final ObjectMapper json;

    public StandardHttpCitationEngine(URI url, Duration timeout, Duration interval, ObjectMapper json) {
        this.http = new CitationEngineHttp(url, timeout, interval, json);
        this.json = json;
    }

    @Override
    public String providerId() {
        return "standard-v1";
    }

    @Override
    public boolean isHealthy() {
        return http.healthy("/v1/health");
    }

    @Override
    public CitationResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled)
            throws InterruptedException {
        var run = http.run(cancelled);
        var body = json.createObjectNode().put("runId", request.runId().toString())
                .put("text", request.text()).put("citationStyle", request.citationStyle().id());
        body.set("retrievalPolicy", json.valueToTree(request.retrievalPolicy()));
        var submitted = run.exchange("POST", RUNS, body, 202);
        var path = http.runPath(RUNS, submitted, "engineRunId");
        try {
            sink.runStarted(submitted.path("engineRunId").asText());
            return poll(run, path, sink, cancelled);
        } catch (InterruptedException error) {
            http.cancel(path);
            throw error;
        } catch (EngineFailure error) {
            if (error.code().equals("CITATION_ENGINE_TIMEOUT") || cancelled.cancelled()) http.cancel(path);
            throw error;
        }
    }

    private CitationResult poll(CitationEngineHttp.Run run, String path, ProgressSink sink, CancellationSignal cancelled)
            throws InterruptedException {
        int delivered = 0;
        CitationUpdate last = null;
        while (true) {
            var reply = run.exchange("GET", path, null, 200);
            var events = reply.path("events");
            for (; delivered < events.size(); delivered++) {
                var update = update(events.get(delivered));
                if (update != null) sink.update(update);
            }
            var status = reply.path("status").asText();
            if (events.isEmpty() && status.equals("RUNNING")) {
                var update = update(reply);
                if (update != null && !update.equals(last)) sink.update(update);
                last = update;
            }
            cancelled.check();
            switch (status) {
                case "SUCCEEDED": return result(reply.path("result"));
                case "FAILED", "CANCELLED": throw engineFailed(reply);
                case "QUEUED", "RUNNING": run.pause(); break;
                default: throw new EngineFailure("CITATION_ENGINE_FAILED", "引擎返回了无效的任务状态");
            }
        }
    }

    private CitationUpdate update(JsonNode event) {
        try {
            var stage = CitationStage.valueOf(event.path("stage").asText());
            if (stage.terminal()) return null;
            var type = CitationUpdate.EventType.valueOf(event.path("eventType").asText("STAGE_STARTED"));
            if (type == COMPLETED || type == FAILED || type == CANCELLED) return null;
            var payload = event.path("payload");
            return new CitationUpdate(stage, type, event.path("progress").asDouble(),
                    event.path("message").asText("引文引擎进度更新"),
                    payload.isObject() ? json.convertValue(payload, new TypeReference<>() {}) : Map.of());
        } catch (IllegalArgumentException unknownEvent) {
            return null;
        }
    }

    private CitationResult result(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw new EngineFailure("CITATION_ENGINE_FAILED", "引擎未返回标注结果");
        }
        var payload = object.deepCopy();
        var selection = payload.remove("initialSelectedReferenceIds");
        if (selection != null) payload.set("selectedReferenceIds", selection);
        try {
            return json.readerFor(CitationResult.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(payload);
        } catch (IOException error) {
            throw new EngineFailure("CITATION_ENGINE_FAILED", "无法读取引擎标注结果");
        }
    }

    private EngineFailure engineFailed(JsonNode reply) {
        var code = reply.path("errorCode").asText("");
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) code = "CITATION_ENGINE_FAILED";
        return new EngineFailure(code, "引文引擎运行失败");
    }
}
