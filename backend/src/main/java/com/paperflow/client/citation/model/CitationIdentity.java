package com.paperflow.client.citation.model;

import java.util.Locale;

public final class CitationIdentity {
    private CitationIdentity() {}

    public static String doi(String doi) {
        if (doi == null || doi.isBlank()) return null;
        var value = doi.strip().toLowerCase(Locale.ROOT)
                .replaceFirst("^(https?://(dx\\.)?doi\\.org/|doi:)\\s*", "");
        return value.isBlank() ? null : value;
    }

    public static String workId(String workId) {
        if (workId == null || workId.isBlank()) return null;
        return workId.strip().replaceFirst("(?i)^https?://openalex\\.org/", "");
    }

    public static String key(CitationReference reference) {
        var doi = doi(reference.doi());
        if (doi != null) return "doi:" + doi;
        var workId = workId(reference.workId());
        if (workId != null) return "work:" + workId;
        if (reference.paperId() != null) return "paper:" + reference.paperId();
        return "reference:" + reference.referenceId();
    }
}
