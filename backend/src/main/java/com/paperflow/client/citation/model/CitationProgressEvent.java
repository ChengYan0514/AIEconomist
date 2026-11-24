package com.paperflow.client.citation.model;

import java.time.Instant;
import java.util.Map;

public record CitationProgressEvent(long eventId, CitationStage stage, CitationUpdate.EventType eventType,
                                    double progress, String message, Map<String, Object> payload, Instant occurredAt) {}
