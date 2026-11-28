package com.paperflow.paper.journal;

import java.time.OffsetDateTime;

public record JournalSummary(
        String sourceId,
        String name,
        long totalPapers,
        long archivedPapers,
        long publishedCount,
        long readyCount,
        long unprocessedCount,
        long failedCount,
        long unsupportedCount,
        long matchedCount,
        long causalExtractedCount,
        long causalNoCausalCount,
        long causalPendingReviewCount,
        long causalExtractingCount,
        long causalFailedCount,
        long causalNotExtractedCount,
        long causalClaimCount,
        Integer yearMin,
        Integer yearMax,
        OffsetDateTime latestUpdatedAt
) {
    public long causalCompletedCount() {
        return causalExtractedCount + causalNoCausalCount;
    }
}
