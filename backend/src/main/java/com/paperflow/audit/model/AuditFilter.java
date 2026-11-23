package com.paperflow.audit.model;

import java.time.OffsetDateTime;

public record AuditFilter(
        OffsetDateTime from, OffsetDateTime to, String actor, String action,
        String objectType, String objectId, AuditOutcome outcome
) {
    public AuditFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("请选择有效的时间范围");
        }
        actor = filterValue(actor, 50);
        action = filterValue(action, 64);
        objectType = filterValue(objectType, 64);
        objectId = filterValue(objectId, 255);
    }

    private static String filterValue(String value, int limit) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        if (trimmed.length() > limit) throw new IllegalArgumentException("审计筛选字段过长");
        return trimmed;
    }
}
