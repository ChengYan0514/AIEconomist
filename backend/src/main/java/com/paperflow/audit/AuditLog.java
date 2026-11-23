package com.paperflow.audit;

import com.paperflow.audit.model.AuditEvent;
import com.paperflow.audit.model.AuditEventNotFound;
import com.paperflow.audit.model.AuditFilter;
import com.paperflow.audit.model.AuditOutcome;

import java.util.List;
import java.util.Map;

public interface AuditLog {
    // Supply an operation-specific summary from explicitly selected fields.
    void record(String actor, String action, String objectType, String objectId, AuditOutcome outcome,
                Map<String, Object> summary);

    List<AuditEvent> list(AuditFilter filter, long offset, int size);
    long count(AuditFilter filter);
    AuditEvent find(long eventId);

    default AuditEvent get(long eventId) {
        if (eventId <= 0) throw new IllegalArgumentException("审计事件 ID 必须为正整数");
        AuditEvent event = find(eventId);
        if (event == null) throw new AuditEventNotFound();
        return event;
    }
}
