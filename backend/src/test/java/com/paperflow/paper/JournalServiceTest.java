package com.paperflow.paper;

import com.paperflow.paper.journal.CorpusJournalOverview;
import com.paperflow.paper.journal.JournalFilter;
import com.paperflow.paper.journal.JournalNotFound;
import com.paperflow.paper.journal.JournalService;
import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalServiceTest {
    private static JournalSummary journal(
            String id, String name, long total, long published, long ready, long unprocessed, long failed,
            long matched, long extracted, long noCausal, long pendingReview, long extracting, long causalFailed,
            long notExtracted, long claims, Integer yearMin, Integer yearMax, OffsetDateTime updatedAt
    ) {
        return new JournalSummary(id, name, total, 0, published, ready, unprocessed, failed, 0, matched,
                extracted, noCausal, pendingReview, extracting, causalFailed, notExtracted, claims,
                yearMin, yearMax, updatedAt);
    }

    @Test
    void normalizesSourceIdAndLoadsDetail() {
        var summary = journal("S123", "Review of Applied Economics", 5, 3, 1, 1, 0, 2, 1, 0, 0, 0, 0, 1, 4,
                2020, 2024, OffsetDateTime.now());
        var years = List.of(new JournalYearStats(2024, 2, 2, 0, 0, 0, 0, 1));
        var store = new StubStore(Map.of("S123", summary), Map.of("S123", 42L), Map.of("S123", years));
        var service = new JournalService(store, "dataset_20241125");

        var detail = service.detail("  s123 ");

        assertThat(detail.journal()).isEqualTo(summary);
        assertThat(detail.importedWorksCount()).isEqualTo(42L);
        assertThat(detail.years()).containsExactlyElementsOf(years);
    }

    @Test
    void rejectsInvalidSourceIdAndUnknownJournal() {
        var service = new JournalService(new StubStore(Map.of(), Map.of(), Map.of()), "dataset_20241125");

        assertThatThrownBy(() -> service.detail("invalid-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detail("S999"))
                .isInstanceOf(JournalNotFound.class);
    }

    @Test
    void validatesFilterLengthAndDefaultsModeAndSort() {
        var filter = new JournalFilter("   ", null, null);
        assertThat(filter.q()).isNull();
        assertThat(filter.mode()).isEqualTo(JournalFilter.Mode.ALL);
        assertThat(filter.sort()).isEqualTo(JournalFilter.Sort.PAPERS_DESC);

        assertThatThrownBy(() -> new JournalFilter("x".repeat(201), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computesDirectoryOverviewFiltersSortsAndCachesSnapshot() {
        var now = OffsetDateTime.now();
        var j1 = journal("S1", "Alpha Economics", 10, 10, 0, 0, 0, 10, 2, 1, 0, 0, 0, 7, 12, 2018, 2024, now.minusDays(1));
        var j2 = journal("S2", "Beta Finance", 4, 2, 1, 0, 1, 3, 3, 0, 1, 0, 0, 0, 20, 2020, 2025, now);
        var store = new StubStore(Map.of("S1", j1, "S2", j2), Map.of(), Map.of());
        var service = new JournalService(store, "dataset_20241125", Duration.ofSeconds(30));

        var byCausal = service.directory(new JournalFilter(null, JournalFilter.Mode.ALL, JournalFilter.Sort.CAUSAL_RATE_DESC), 0, 10);
        assertThat(byCausal.total()).isEqualTo(2);
        assertThat(byCausal.items()).extracting(JournalSummary::sourceId).containsExactly("S2", "S1");
        assertThat(byCausal.overview().totalJournals()).isEqualTo(2);
        assertThat(byCausal.overview().totalPapers()).isEqualTo(14);
        assertThat(byCausal.overview().causalExtractedPapers()).isEqualTo(5);
        assertThat(byCausal.overview().causalNoCausalPapers()).isEqualTo(1);
        assertThat(byCausal.overview().causalPendingReviewPapers()).isEqualTo(1);
        assertThat(byCausal.overview().causalNotExtractedPapers()).isEqualTo(7);
        assertThat(byCausal.overview().causalClaims()).isEqualTo(32);
        assertThat(byCausal.overview().yearMin()).isEqualTo(2018);
        assertThat(byCausal.overview().yearMax()).isEqualTo(2025);

        var onlyFailed = service.directory(new JournalFilter("beta", JournalFilter.Mode.HAS_FAILED, null), 0, 10);
        assertThat(onlyFailed.total()).isEqualTo(1);
        assertThat(onlyFailed.items()).extracting(JournalSummary::sourceId).containsExactly("S2");
        assertThat(store.listAllCalls.get()).isEqualTo(1);

        var uncached = new JournalService(store, "dataset_20241125", Duration.ZERO);
        uncached.directory(new JournalFilter(null, null, null), 0, 10);
        uncached.directory(new JournalFilter(null, null, null), 0, 10);
        assertThat(store.listAllCalls.get()).isEqualTo(3);
    }

    @Test
    void fallsBackToEmptyOverviewWhenStoreReturnsEmpty() {
        var service = new JournalService(new StubStore(Map.of(), Map.of(), Map.of()), "dataset_20241125");
        var dir = service.directory(new JournalFilter(null, null, null), 0, 10);
        assertThat(dir.items()).isEmpty();
        assertThat(dir.total()).isZero();
        assertThat(dir.overview()).isEqualTo(CorpusJournalOverview.empty());
    }

    private static final class StubStore implements JournalStore {
        private final Map<String, JournalSummary> journals;
        private final Map<String, Long> importedCounts;
        private final Map<String, List<JournalYearStats>> yearly;
        final AtomicInteger listAllCalls = new AtomicInteger();

        StubStore(Map<String, JournalSummary> journals, Map<String, Long> importedCounts,
                  Map<String, List<JournalYearStats>> yearly) {
            this.journals = journals;
            this.importedCounts = importedCounts;
            this.yearly = yearly;
        }

        @Override public List<JournalSummary> listAll(String schema) {
            listAllCalls.incrementAndGet();
            return List.copyOf(journals.values());
        }
        @Override public JournalSummary get(String schema, String sourceId) { return journals.get(sourceId); }
        @Override public long importedWorksCount(String sourceId) { return importedCounts.getOrDefault(sourceId, 0L); }
        @Override public List<JournalYearStats> years(String sourceId) { return yearly.getOrDefault(sourceId, List.of()); }
    }
}
