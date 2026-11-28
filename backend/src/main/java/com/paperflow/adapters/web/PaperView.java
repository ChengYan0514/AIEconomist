package com.paperflow.adapters.web;

import com.paperflow.paper.model.CausalStatus;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaperView(
        String paperId, String sourceId, String sourceName, Integer year, String title, String authors,
        String doi, String url, String provider, String originalName, String originalType, Long originalSize,
        long version, long revision, boolean archived, boolean legacy, boolean hasOriginal, String matchedWorkId,
        UUID latestParseId, UUID publishedParseId, PaperStatus status, CausalStatus causalStatus,
        int causalClaimCount, OffsetDateTime updatedAt
) {
    static PaperView from(Paper paper, boolean hasOriginal) {
        return new PaperView(paper.paperId(), paper.sourceId(), paper.sourceName(), paper.year(), paper.title(),
                paper.authors(), paper.doi(), paper.url(), paper.provider(), paper.originalName(), paper.originalType(),
                paper.originalSize(), paper.version(), paper.revision(), paper.archived(), paper.legacy(), hasOriginal,
                paper.matchedWorkId(), paper.latestParseId(), paper.publishedParseId(), paper.status(),
                paper.causalStatus(), paper.causalClaimCount(), paper.updatedAt());
    }
}
