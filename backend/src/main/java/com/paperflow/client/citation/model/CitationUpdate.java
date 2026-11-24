package com.paperflow.client.citation.model;

import java.util.Map;

public record CitationUpdate(CitationStage stage, EventType eventType, double progress, String message,
                             Map<String, Object> payload) {
    public CitationUpdate(CitationStage stage, EventType eventType, double progress, String message) {
        this(stage, eventType, progress, message, Map.of());
    }

    public enum EventType { STATUS_CHANGED, STAGE_STARTED, STAGE_COMPLETED, WARNING, COMPLETED, FAILED, CANCELLED }
}
