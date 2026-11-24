package com.paperflow.causal.model;

public enum RunStatus {
    QUEUED,
    RUNNING,
    PUBLISHING,
    SUCCEEDED,
    SKIPPED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == PUBLISHING;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
