package com.paperflow.paper;

import com.paperflow.paper.journal.CorpusJournalOverview;
import com.paperflow.paper.journal.JournalFilter;
import com.paperflow.paper.journal.JournalNotFound;
import com.paperflow.paper.journal.JournalService;
import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalServiceTest {
    @Test
    void normalizesSourceIdAndLoadsDetail() {
        var summary = new JournalSummary("S123", "Review of Applied Economics", 5, 1, 3, 1, 1, 0, 0, 2, 2020, 2024, OffsetDateTime.now());
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
    void fallsBackToEmptyOverviewWhenStoreReturnsNull() {
        var service = new JournalService(new StubStore(Map.of(), Map.of(), Map.of()), "dataset_20241125");
        assertThat(service.overview()).isEqualTo(CorpusJournalOverview.empty());
    }

    private record StubStore(
            Map<String, JournalSummary> journals,
            Map<String, Long> importedCounts,
            Map<String, List<JournalYearStats>> yearly
    ) implements JournalStore {
        @Override public CorpusJournalOverview overview(String schema) { return null; }
        @Override public List<JournalSummary> list(String schema, JournalFilter filter, long offset, int size) { return List.copyOf(journals.values()); }
        @Override public long count(String schema, JournalFilter filter) { return journals.size(); }
        @Override public JournalSummary get(String schema, String sourceId) { return journals.get(sourceId); }
        @Override public long importedWorksCount(String sourceId) { return importedCounts.getOrDefault(sourceId, 0L); }
        @Override public List<JournalYearStats> years(String sourceId) { return yearly.getOrDefault(sourceId, List.of()); }
    }
}
