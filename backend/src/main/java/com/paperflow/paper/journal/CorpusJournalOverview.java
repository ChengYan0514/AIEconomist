package com.paperflow.paper.journal;

import java.util.List;

public record CorpusJournalOverview(
        long totalJournals,
        long totalPapers,
        long publishedPapers,
        long matchedPapers,
        long failedPapers,
        long causalExtractedPapers,
        long causalNoCausalPapers,
        long causalPendingReviewPapers,
        long causalExtractingPapers,
        long causalFailedPapers,
        long causalNotExtractedPapers,
        long causalClaims,
        Integer yearMin,
        Integer yearMax
) {
    public static CorpusJournalOverview empty() {
        return new CorpusJournalOverview(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
    }

    public static CorpusJournalOverview fromJournals(List<JournalSummary> journals) {
        if (journals == null || journals.isEmpty()) return empty();
        long papers = 0, published = 0, matched = 0, failed = 0;
        long extracted = 0, noCausal = 0, pendingReview = 0, extracting = 0, causalFailed = 0, notExtracted = 0, claims = 0;
        Integer minYear = null, maxYear = null;
        for (JournalSummary j : journals) {
            papers += j.totalPapers();
            published += j.publishedCount();
            matched += j.matchedCount();
            failed += j.failedCount();
            extracted += j.causalExtractedCount();
            noCausal += j.causalNoCausalCount();
            pendingReview += j.causalPendingReviewCount();
            extracting += j.causalExtractingCount();
            causalFailed += j.causalFailedCount();
            notExtracted += j.causalNotExtractedCount();
            claims += j.causalClaimCount();
            if (j.yearMin() != null && (minYear == null || j.yearMin() < minYear)) minYear = j.yearMin();
            if (j.yearMax() != null && (maxYear == null || j.yearMax() > maxYear)) maxYear = j.yearMax();
        }
        return new CorpusJournalOverview(journals.size(), papers, published, matched, failed,
                extracted, noCausal, pendingReview, extracting, causalFailed, notExtracted, claims, minYear, maxYear);
    }
}
