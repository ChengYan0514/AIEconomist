package com.paperflow.adapters.web;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;

import java.util.Map;

public final class AuditAttempt {
    private final AuditLog audit;

    public AuditAttempt(AuditLog audit) {
        this.audit = audit;
    }

    <T, E extends Exception> T run(String actor, String action, String type, String id, Operation<T, E> operation) throws E {
        try {
            return operation.run();
        } catch (WorkspaceAccess.Denied denial) {
            throw denial;
        } catch (Exception error) {
            preserveFailure(error, () -> {
                String objectId = id.length() > 255 || id.indexOf('\0') >= 0 ? "unknown" : id;
                audit.record(actor, action, type, objectId, AuditOutcome.FAILED, Map.of("code", ApiErrors.code(error)));
            });
            throw error;
        }
    }

    static void preserveFailure(Exception error, Runnable record) {
        try {
            record.run();
        } catch (RuntimeException auditError) {
            error.addSuppressed(auditError);
        }
    }

    @FunctionalInterface
    interface Operation<T, E extends Exception> {
        T run() throws E;
    }
}
