package com.paperflow.client.citation.model;

public enum CitationTaskStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
