package com.paperflow.paper.journal;

public record CorpusJournalOverview(
        long totalJournals,
        long totalPapers,
        long publishedPapers,
        long matchedPapers,
        long failedPapers,
        Integer yearMin,
        Integer yearMax
) {
    public static CorpusJournalOverview empty() {
        return new CorpusJournalOverview(0, 0, 0, 0, 0, null, null);
    }
}
