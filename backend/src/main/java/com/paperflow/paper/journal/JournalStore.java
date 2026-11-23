package com.paperflow.paper.journal;

import java.util.List;

public interface JournalStore {
    CorpusJournalOverview overview(String schema);
    List<JournalSummary> list(String schema, JournalFilter filter, long offset, int size);
    long count(String schema, JournalFilter filter);
    JournalSummary get(String schema, String sourceId);
    long importedWorksCount(String sourceId);
    List<JournalYearStats> years(String sourceId);
}
