package com.paperflow.paper.journal;

import java.util.List;

public interface JournalStore {
    List<JournalSummary> listAll(String schema);
    JournalSummary get(String schema, String sourceId);
    long importedWorksCount(String sourceId);
    List<JournalYearStats> years(String sourceId);
}
