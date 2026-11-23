package com.paperflow.fulltext;

import java.util.List;
import java.util.UUID;

public interface FullTextStore {
    ParseArtifact artifact(UUID id);
    String legacyContent(String paperId);
    int addArtifact(ParseArtifact artifact);
    int markParsed(String paperId, UUID parseId);
    int addBlocks(UUID parseId, List<ContentBlock> blocks);
    int markPublished(String paperId, UUID parseId);
    List<ContentBlock> blocks(UUID parseId, int size, long offset);
    long countBlocks(UUID parseId);
    ContentBlock block(UUID parseId, int sequence);
    List<ContentBlock> legacyBlocks(String paperId, int size, long offset);
    long countLegacy(String paperId);
    ContentBlock legacyBlock(String paperId, int sequence);
}
