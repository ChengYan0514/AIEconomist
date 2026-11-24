package com.paperflow.client.adapters.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationSubmission;
import com.paperflow.client.citation.model.RetrievalPolicy;
import com.paperflow.client.model.ClientInvalid;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class CitationTaskRequests {
    private final ObjectMapper json;

    CitationTaskRequests(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.json.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        this.json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    CitationSubmission submission(JsonNode body) {
        if (body.has("retrievalPolicy") && body.get("retrievalPolicy").isNull()) {
            throw new ClientInvalid("检索范围必须是对象");
        }
        var request = read(body, Submission.class);
        return new CitationSubmission(request.title(), request.text(), CitationStyle.parse(request.citationStyle()),
                request.retrievalPolicy());
    }

    Review review(JsonNode body) {
        if (body.has("citationStyle") && !body.get("citationStyle").isTextual()) {
            throw new ClientInvalid("不支持的引文样式");
        }
        if (body.has("selectedReferenceIds") && !body.get("selectedReferenceIds").isObject()) {
            throw new ClientInvalid("审阅的参考文献选择必须是对象");
        }
        return read(body, Review.class);
    }

    private <T> T read(JsonNode body, Class<T> type) {
        if (!body.isObject()) throw new ClientInvalid("请求正文必须是对象");
        try {
            return json.readerFor(type).readValue(body);
        } catch (IOException error) {
            if (error.getCause() instanceof ClientInvalid invalid) throw invalid;
            throw new ClientInvalid("请求正文格式无效");
        }
    }

    record Submission(String title, String text, String citationStyle, RetrievalPolicy retrievalPolicy) {}

    record Review(String citationStyle, Map<String, List<String>> selectedReferenceIds) {
        Review {
            selectedReferenceIds = selectedReferenceIds == null ? Map.of() : selectedReferenceIds;
        }

        CitationStyle style() {
            return citationStyle == null ? null : CitationStyle.parse(citationStyle);
        }
    }
}
