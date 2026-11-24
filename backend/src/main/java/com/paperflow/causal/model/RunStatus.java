package com.paperflow.causal.model;

public enum RunStatus {
    QUEUED,
    RUNNING,
    PENDING_REVIEW,
    PUBLISHING,
    SUCCEEDED,
    SKIPPED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == PENDING_REVIEW || this == PUBLISHING;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
