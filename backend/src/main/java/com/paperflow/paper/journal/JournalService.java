package com.paperflow.paper.journal;

import com.paperflow.paper.model.PaperMetadata;

import java.util.List;

public final class JournalService {
    private final JournalStore store;
    private final String openalexSchema;

    public JournalService(JournalStore store, String openalexSchema) {
        this.store = store;
        this.openalexSchema = openalexSchema;
    }

    public CorpusJournalOverview overview() {
        CorpusJournalOverview result = store.overview(openalexSchema);
        return result == null ? CorpusJournalOverview.empty() : result;
    }

    public List<JournalSummary> list(JournalFilter filter, long offset, int size) {
        return store.list(openalexSchema, filter, offset, size);
    }

    public long count(JournalFilter filter) {
        return store.count(openalexSchema, filter);
    }

    public JournalDetail detail(String rawSourceId) {
        String sourceId = PaperMetadata.source(rawSourceId);
        JournalSummary journal = store.get(openalexSchema, sourceId);
        if (journal == null) throw new JournalNotFound();
        long importedWorks = store.importedWorksCount(sourceId);
        List<JournalYearStats> years = store.years(sourceId);
        return new JournalDetail(journal, importedWorks, years);
    }
}
