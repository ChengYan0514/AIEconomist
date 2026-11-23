package com.paperflow.fulltext;

import java.util.UUID;

public record ParseArtifact(
        UUID parseId, String paperId, long fileRevision, boolean legacy, String storagePath,
        String contentPath, String markdownPath
) {}
