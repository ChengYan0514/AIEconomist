package com.paperflow.paper.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Paper(
        String paperId, boolean legacy, String sourceId, String sourceName, Integer year,
        String title, String authors, String doi, String url, String provider, String originalName,
        String originalPath, String originalType, Long originalSize, long revision, long version,
        boolean archived, String matchedWorkId, UUID latestParseId, UUID publishedParseId,
        PaperStatus status, OffsetDateTime updatedAt
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
}
