package com.paperflow.audit.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record AuditEvent(
        long eventId, OffsetDateTime occurredAt, String actor, String action,
        String objectType, String objectId, AuditOutcome outcome, Map<String, Object> summary
) {}
