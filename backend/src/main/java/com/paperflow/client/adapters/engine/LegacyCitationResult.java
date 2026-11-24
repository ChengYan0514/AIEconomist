package com.paperflow.client.adapters.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStatement;
import com.paperflow.client.citation.model.EvidenceChunk;
import com.paperflow.client.citation.model.EvidenceStats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class LegacyCitationResult {
    private LegacyCitationResult() {}

    static CitationResult read(JsonNode reply) {
        return new CitationResult(array(reply.path("claims")).map(LegacyCitationResult::statement).toList(),
                byStatement(reply.path("supported_references"), list -> array(list).map(LegacyCitationResult::reference).toList()),
                byStatement(reply.path("selected_references"), LegacyCitationResult::selection),
                byStatement(reply.path("evidence_stats"), LegacyCitationResult::stats),
                array(reply.path("warnings")).map(JsonNode::asText).toList());
    }

    private static CitationStatement statement(JsonNode value) {
        return new CitationStatement(value.path("claim_id").asText(), value.path("text").asText(),
                value.path("start_char").asInt(), value.path("end_char").asInt(),
                value.path("needs_citation").asBoolean(true), text(value, "query"), number(value, "confidence"));
    }

    private static CitationReference reference(JsonNode value) {
        var metadata = value.path("metadata");
        var chunks = array(value.path("chunks")).map(LegacyCitationResult::chunk).toList();
        if (chunks.isEmpty() && text(value, "chunk_text") != null) chunks = List.of(chunk(value));
        var workId = text(value, "work_id");
        var sourceId = text(value, "source_id");
        return new CitationReference(value.path("reference_id").asText(), workId == null ? text(metadata, "work_id") : workId,
                null, text(value, "doi"), text(value, "title"), array(value.path("authors")).map(JsonNode::asText).toList(),
                value.path("year").isIntegralNumber() ? value.path("year").intValue() : null, text(value, "venue"),
                sourceId == null ? text(metadata, "source_id") : sourceId, text(value, "url"),
                CitationReference.MetadataSource.ENGINE, number(value, "score"),
                chunks.stream().map(EvidenceChunk::supportScore).filter(java.util.Objects::nonNull).max(Double::compare).orElse(null),
                chunks.stream().map(EvidenceChunk::confidence).filter(java.util.Objects::nonNull).max(Double::compare).orElse(null),
                chunks);
    }

    private static EvidenceChunk chunk(JsonNode value) {
        var metadata = value.path("metadata");
        var section = text(value, "section_title");
        return new EvidenceChunk(value.path("chunk_id").asText(""), section == null ? text(metadata, "section_title") : section,
                value.path("chunk_text").asText(""), number(value, "score"), number(metadata, "evidence_score"),
                number(metadata, "evidence_confidence"), text(metadata, "evidence_reason"));
    }

    private static List<String> selection(JsonNode list) {
        return array(list).map(value -> value.isTextual() ? value.asText() : value.path("reference_id").asText())
                .filter(id -> !id.isBlank()).distinct().toList();
    }

    private static EvidenceStats stats(JsonNode value) {
        var rejected = value.path("rejected_chunks");
        return new EvidenceStats(value.path("judged_chunks").asInt(), value.path("supported_chunks").asInt(),
                value.path("supported_references").asInt(), new EvidenceStats.Rejected(rejected.path("partial").asInt(),
                rejected.path("contradicts").asInt(), rejected.path("irrelevant").asInt(),
                rejected.path("low_score").asInt(), rejected.path("low_confidence").asInt()),
                value.path("hit_hard_recall_limit").asBoolean(), value.path("hit_hard_judgment_limit").asBoolean());
    }

    private static <T> Map<String, T> byStatement(JsonNode object, Function<JsonNode, T> convert) {
        var result = new LinkedHashMap<String, T>();
        object.fields().forEachRemaining(entry -> result.put(entry.getKey(), convert.apply(entry.getValue())));
        return result;
    }

    private static Stream<JsonNode> array(JsonNode node) {
        return node.isArray() ? StreamSupport.stream(node.spliterator(), false) : Stream.empty();
    }

    private static String text(JsonNode value, String field) {
        return value.path(field).asText(null);
    }

    private static Double number(JsonNode value, String field) {
        return value.path(field).isNumber() ? value.path(field).doubleValue() : null;
    }
}
