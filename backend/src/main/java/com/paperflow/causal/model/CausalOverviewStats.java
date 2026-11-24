package com.paperflow.causal.model;

public record CausalOverviewStats(
        long succeededPapers, // EXTRACTED + EXTRACTED_NO_CAUSAL
        long unextractedPapers, // NOT_EXTRACTED + PREREQUISITE_MISSING
        long failedPapers, // FAILED
        long activePapers, // EXTRACTING + PENDING_REVIEW
        long totalClaims
) {}
