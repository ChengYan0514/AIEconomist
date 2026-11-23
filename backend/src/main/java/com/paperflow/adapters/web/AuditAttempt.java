package com.paperflow.adapters.web;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.job.JobConflict;
import com.paperflow.job.JobNotFound;
import com.paperflow.paper.model.PaperConflict;
import com.paperflow.paper.model.PaperNotFound;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;

public final class AuditAttempt {
    private final AuditLog audit;

    public AuditAttempt(AuditLog audit) {
        this.audit = audit;
    }

    <T, E extends Exception> T run(String actor, String action, String type, String id, Operation<T, E> operation) throws E {
        try {
            return operation.run();
        } catch (Exception error) {
            try {
                String objectId = id.length() > 255 || id.indexOf('\0') >= 0 ? "unknown" : id;
                audit.record(actor, action, type, objectId, AuditOutcome.FAILED, Map.of("code", code(error)));
            } catch (RuntimeException auditError) {
                error.addSuppressed(auditError);
            }
            throw error;
        }
    }

    private String code(Exception error) {
        if (error instanceof PaperConflict conflict) return conflict.code();
        if (error instanceof PaperNotFound) return "PAPER_NOT_FOUND";
        if (error instanceof JobConflict) return "JOB_CONFLICT";
        if (error instanceof JobNotFound) return "JOB_NOT_FOUND";
        if (error instanceof DuplicateKeyException) return "CONCURRENT_CHANGE";
        if (error instanceof IllegalArgumentException) return "INVALID_INPUT";
        if (error instanceof NoSuchFileException) return "FILE_MISSING";
        if (error instanceof IOException) return "FILE_PROCESSING_FAILED";
        return "INTERNAL";
    }

    @FunctionalInterface
    interface Operation<T, E extends Exception> {
        T run() throws E;
    }
}
