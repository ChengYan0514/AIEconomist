package com.paperflow.audit.model;

public final class AuditEventNotFound extends RuntimeException {
    public AuditEventNotFound() {
        super("找不到审计事件");
    }
}
