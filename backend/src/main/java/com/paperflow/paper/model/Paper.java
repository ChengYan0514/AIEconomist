package com.paperflow.paper.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Paper(
        String paperId, boolean legacy, String sourceId, String sourceName, Integer year,
        String title, String authors, String doi, String url, String provider, String originalName,
        String originalPath, String originalType, Long originalSize, long revision, long version,
        boolean archived, String matchedWorkId, UUID latestParseId, UUID publishedParseId,
        PaperStatus status, CausalStatus causalStatus, int causalClaimCount, OffsetDateTime updatedAt
) {
    public boolean usesLegacyFile() {
        return legacy && revision == 0;
    }

    public void requireCurrent(long expectedVersion) {
        if (expectedVersion != version) {
            throw new PaperConflict("PAPER_CHANGED", "论文已更新，请刷新后重试");
        }
    }

    public void requireActive() {
        if (archived) throw new PaperConflict("PAPER_ARCHIVED", "请先恢复已归档的论文");
    }

    public Paper withCausalClaims(int claimCount) {
        if (claimCount <= 0) return this;
        CausalStatus resolved = (causalStatus == CausalStatus.EXTRACTING || causalStatus == CausalStatus.PENDING_REVIEW)
                ? causalStatus : CausalStatus.EXTRACTED;
        return new Paper(paperId, legacy, sourceId, sourceName, year, title, authors, doi, url, provider,
                originalName, originalPath, originalType, originalSize, revision, version, archived,
                matchedWorkId, latestParseId, publishedParseId, status, resolved, claimCount, updatedAt);
    }
}
