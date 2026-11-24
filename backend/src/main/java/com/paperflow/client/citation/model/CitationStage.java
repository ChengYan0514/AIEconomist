package com.paperflow.client.citation.model;

public enum CitationStage {
    QUEUED, PARSE_TEXT, PLAN_RETRIEVAL, RETRIEVE_REFERENCES, RANK_EVIDENCE, ENRICH_METADATA,
    FORMAT_OUTPUT, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() {
        return ordinal() >= COMPLETED.ordinal();
    }
}
