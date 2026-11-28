package com.paperflow.paper.journal;

import com.paperflow.paper.model.PaperMetadata;

import java.time.Duration;
import java.util.List;

public final class JournalService {
    private final JournalStore store;
    private final String openalexSchema;
    private final long cacheTtlNanos;
    private volatile CachedSnapshot cache;

    public JournalService(JournalStore store, String openalexSchema) {
        this(store, openalexSchema, Duration.ZERO);
    }

    public JournalService(JournalStore store, String openalexSchema, Duration cacheTtl) {
        this.store = store;
        this.openalexSchema = openalexSchema;
        this.cacheTtlNanos = cacheTtl == null || cacheTtl.isNegative() ? 0L : cacheTtl.toNanos();
    }

    public JournalDirectory directory(JournalFilter filter, long offset, int size) {
        List<JournalSummary> all = loadAll();
        CorpusJournalOverview overview = CorpusJournalOverview.fromJournals(all);
        List<JournalSummary> matched = all.stream()
                .filter(filter::matches)
                .sorted(filter.comparator())
                .toList();
        int from = (int) Math.min(Math.max(offset, 0), matched.size());
        int to = (int) Math.min(from + Math.max(size, 0), matched.size());
        return new JournalDirectory(matched.subList(from, to), matched.size(), overview);
    }

    public JournalDetail detail(String rawSourceId) {
        String sourceId = PaperMetadata.source(rawSourceId);
        JournalSummary journal = store.get(openalexSchema, sourceId);
        if (journal == null) throw new JournalNotFound();
        long importedWorks = store.importedWorksCount(sourceId);
        List<JournalYearStats> years = store.years(sourceId);
        return new JournalDetail(journal, importedWorks, years);
    }

    private List<JournalSummary> loadAll() {
        if (cacheTtlNanos <= 0L) {
            return normalize(store.listAll(openalexSchema));
        }
        long now = System.nanoTime();
        CachedSnapshot snapshot = cache;
        if (snapshot != null && now - snapshot.loadedAtNanos < cacheTtlNanos) {
            return snapshot.journals;
        }
        synchronized (this) {
            now = System.nanoTime();
            snapshot = cache;
            if (snapshot != null && now - snapshot.loadedAtNanos < cacheTtlNanos) {
                return snapshot.journals;
            }
            List<JournalSummary> fresh = normalize(store.listAll(openalexSchema));
            cache = new CachedSnapshot(fresh, System.nanoTime());
            return fresh;
        }
    }

    private static List<JournalSummary> normalize(List<JournalSummary> items) {
        return items == null ? List.of() : List.copyOf(items);
    }

    private record CachedSnapshot(List<JournalSummary> journals, long loadedAtNanos) {}
}
