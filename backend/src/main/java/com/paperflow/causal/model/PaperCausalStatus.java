package com.paperflow.causal.model;

import java.util.UUID;

public record PaperCausalStatus(
        String paperId,
        String workId,
        String sourceId,
        String paperTitle,
        String status, // EXTRACTING, PENDING_REVIEW, EXTRACTED, EXTRACTED_NO_CAUSAL, FAILED, PREREQUISITE_MISSING, NOT_EXTRACTED
        int claimCount,
        UUID activeBatchId,
        UUID activeRunId,
        String errorCode,
        String causalLog
) {}
