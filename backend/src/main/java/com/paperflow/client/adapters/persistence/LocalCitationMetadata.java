package com.paperflow.client.adapters.persistence;

import com.paperflow.client.citation.model.CitationReference;

import java.util.List;

import static com.paperflow.client.citation.model.CitationReference.MetadataSource.*;

record LocalCitationMetadata(String workId, String paperId, String doi, String title, List<String> authors,
                             Integer year, String venue, String sourceId, String url) {
    CitationReference enrich(CitationReference engine, LocalCitationMetadata paper) {
        var local = combine(paper);
        var source = engineFallback(local, engine) ? LOCAL_DB_AND_ENGINE : LOCAL_DB;
        return new CitationReference(engine.referenceId(), first(local.workId, engine.workId()), local.paperId,
                first(local.doi, engine.doi()), first(local.title, engine.title()),
                local.authors.isEmpty() ? engine.authors() : local.authors, local.year == null ? engine.year() : local.year,
                first(local.venue, engine.venue()), first(local.sourceId, engine.sourceId()), first(local.url, engine.url()),
                source, engine.rankScore(), engine.maxSupportScore(), engine.maxConfidence(), engine.chunks());
    }

    private LocalCitationMetadata combine(LocalCitationMetadata paper) {
        if (paper == null) return this;
        return new LocalCitationMetadata(first(workId, paper.workId), paper.paperId, first(doi, paper.doi),
                first(title, paper.title), authors.isEmpty() ? paper.authors : authors, year == null ? paper.year : year,
                first(venue, paper.venue), first(sourceId, paper.sourceId), first(url, paper.url));
    }

    private boolean engineFallback(LocalCitationMetadata local, CitationReference engine) {
        return fallback(local.workId, engine.workId()) || fallback(local.doi, engine.doi())
                || fallback(local.title, engine.title()) || (local.authors.isEmpty() && !engine.authors().isEmpty())
                || (local.year == null && engine.year() != null) || fallback(local.venue, engine.venue())
                || fallback(local.sourceId, engine.sourceId()) || fallback(local.url, engine.url());
    }

    private static boolean fallback(String local, String engine) {
        return blank(local) && !blank(engine);
    }

    private static String first(String primary, String fallback) {
        return blank(primary) ? (blank(fallback) ? null : fallback) : primary;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
