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
        Integer yearMin,
        Integer yearMax,
        OffsetDateTime latestUpdatedAt
) {}
