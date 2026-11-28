package com.paperflow.paper.model;

public enum CausalStatus {
    EXTRACTING,
    PENDING_REVIEW,
    EXTRACTED,
    EXTRACTED_NO_CAUSAL,
    FAILED,
    PREREQUISITE_MISSING,
    NOT_EXTRACTED;

    public static CausalStatus resolve(String runStatus, int claimCount, PaperStatus paperStatus, String matchedWorkId) {
        if ("QUEUED".equals(runStatus) || "RUNNING".equals(runStatus) || "PUBLISHING".equals(runStatus)) {
            return EXTRACTING;
        }
        if ("PENDING_REVIEW".equals(runStatus)) {
            return PENDING_REVIEW;
        }
        if (claimCount > 0) {
            return EXTRACTED;
        }
        if ("FAILED".equals(runStatus)) {
            return FAILED;
        }
        if ("SUCCEEDED".equals(runStatus)) {
            return EXTRACTED_NO_CAUSAL;
        }
        if (matchedWorkId == null || matchedWorkId.isBlank() || paperStatus != PaperStatus.PUBLISHED) {
            return PREREQUISITE_MISSING;
        }
        return NOT_EXTRACTED;
    }
}
