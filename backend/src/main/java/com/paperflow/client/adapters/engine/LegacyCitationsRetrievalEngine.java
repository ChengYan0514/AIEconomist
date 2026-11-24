package com.paperflow.client.adapters.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.citation.CitationEngine;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationUpdate;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static com.paperflow.client.citation.model.CitationStage.*;
import static com.paperflow.client.citation.model.CitationUpdate.EventType.*;

public final class LegacyCitationsRetrievalEngine implements CitationEngine {
    private static final String JOBS = "/api/citation-jobs";
    private final CitationEngineHttp http;
    private final ObjectMapper json;

    public LegacyCitationsRetrievalEngine(URI url, Duration timeout, Duration interval, ObjectMapper json) {
        this.http = new CitationEngineHttp(url, timeout, interval, json);
        this.json = json;
    }

    @Override
    public String providerId() {
        return "citations-retrieval-v1";
    }

    @Override
    public boolean isHealthy() {
        return http.healthy("/health");
    }

    @Override
    public CitationResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled)
            throws InterruptedException {
        var run = http.run(cancelled);
        var submitted = run.exchange("POST", JOBS, body(request), 201);
        var path = http.runPath(JOBS, submitted, "job_id");
        sink.runStarted(submitted.path("job_id").asText());
        int delivered = 0;
        while (true) {
            var reply = run.exchange("GET", path, null, 200);
            var events = run.exchange("GET", path + "/events", null, 200);
            for (; delivered < events.size(); delivered++) {
                publish(events.get(delivered), sink);
                cancelled.check();
            }
            switch (reply.path("status").asText()) {
                case "succeeded": return LegacyCitationResult.read(reply);
                case "failed": throw new EngineFailure("CITATION_ENGINE_FAILED", "引文引擎运行失败");
                case "pending", "running": run.pause(); break;
                default: throw new EngineFailure("CITATION_ENGINE_FAILED", "引擎返回了无效的任务状态");
            }
        }
    }

    private JsonNode body(EngineRequest request) {
        var body = json.createObjectNode().put("text", request.text()).put("citation_style", request.citationStyle().id());
        var policy = request.retrievalPolicy();
        var filters = body.putObject("retrieval_policy");
        if (!policy.sourceIds().isEmpty()) filters.set("source_ids", json.valueToTree(policy.sourceIds()));
        if (policy.yearMin() != null) filters.put("year_min", policy.yearMin());
        if (policy.yearMax() != null) filters.put("year_max", policy.yearMax());
        return body;
    }

    private void publish(JsonNode event, ProgressSink sink) {
        var stage = switch (event.path("node").asText()) {
            case "parse_text" -> PARSE_TEXT;
            case "plan_retrieval" -> PLAN_RETRIEVAL;
            case "retrieve_references" -> RETRIEVE_REFERENCES;
            case "rank_evidence" -> RANK_EVIDENCE;
            case "enrich_metadata" -> ENRICH_METADATA;
            case "insert_citations", "format_output" -> FORMAT_OUTPUT;
            default -> null;
        };
        var type = switch (event.path("event_type").asText()) {
            case "node_started" -> STAGE_STARTED;
            case "node_completed" -> STAGE_COMPLETED;
            case "warning" -> WARNING;
            default -> null;
        };
        if (type == null || (stage == null && type != WARNING)) return;
        sink.update(new CitationUpdate(stage == null ? QUEUED : stage, type, progress(stage),
                event.path("message").asText("引文引擎进度更新"), payload(event.path("payload"))));
    }

    private double progress(CitationStage stage) {
        if (stage == null) return 0;
        return switch (stage) {
            case PARSE_TEXT -> 0.20;
            case PLAN_RETRIEVAL -> 0.30;
            case RETRIEVE_REFERENCES -> 0.55;
            case RANK_EVIDENCE -> 0.75;
            case ENRICH_METADATA -> 0.85;
            case FORMAT_OUTPUT -> 0.95;
            default -> 0;
        };
    }

    private Map<String, Object> payload(JsonNode value) {
        if (!value.isObject()) return Map.of();
        return json.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
