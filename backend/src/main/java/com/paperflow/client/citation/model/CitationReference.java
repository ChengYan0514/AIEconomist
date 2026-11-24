package com.paperflow.client.citation.model;

import java.util.List;

public record CitationReference(String referenceId, String workId, String paperId, String doi, String title,
                                List<String> authors, Integer year, String venue, String sourceId, String url,
                                MetadataSource metadataSource, Double rankScore, Double maxSupportScore,
                                Double maxConfidence, List<EvidenceChunk> chunks) {
    public CitationReference {
        title = title == null || title.isBlank() ? "Untitled" : title;
        authors = authors == null ? List.of() : authors.stream().filter(a -> a != null && !a.isBlank()).toList();
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        metadataSource = metadataSource == null ? MetadataSource.ENGINE : metadataSource;
    }

    public enum MetadataSource { LOCAL_DB, ENGINE, LOCAL_DB_AND_ENGINE }
}
