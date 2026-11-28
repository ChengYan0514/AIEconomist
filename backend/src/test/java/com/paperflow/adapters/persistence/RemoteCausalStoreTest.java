package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.causal.graph.CausalFactReader.NamedCountRow;
import com.paperflow.causal.graph.CausalFactReader.PaperSummaryRow;
import com.paperflow.paper.model.CausalStatus;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteCausalStoreTest {
    @Test
    void snapshotFiltersInvalidRowsAndRemotePaperStoreAvoidsFullScanWhenUnneeded() {
        CausalFactReader facts = mock(CausalFactReader.class);
        when(facts.listPaperClaimCounts()).thenReturn(List.of(
                new NamedCountRow("W1", 3L),
                new NamedCountRow(" ", 2L),
                new NamedCountRow("W2", 0L),
                new NamedCountRow("W3", 7L)
        ));
        var snapshot = CausalClaimSnapshot.from(facts);
        assertThat(snapshot.workIds()).containsExactly("W1", "W3");
        assertThat(snapshot.counts()).containsExactly(3L, 7L);

        PaperMapper mapper = mock(PaperMapper.class);
        var basePaper = new Paper("p1", false, "S1", "J", 2024, "Title", "Alice", null, null, null,
                "a.pdf", "v2/a.pdf", "PDF", 10L, 1, 1, false, "W1", null, null,
                PaperStatus.PUBLISHED, CausalStatus.NOT_EXTRACTED, 0, OffsetDateTime.now());
        when(mapper.getWithClaims("s", "p1", CausalClaimSnapshot.EMPTY)).thenReturn(basePaper);
        when(facts.findPaperSummary("W1")).thenReturn(new PaperSummaryRow(3L, 2L, 3L));

        var store = new RemoteCausalPaperStore(mapper, facts);
        var loaded = store.get("s", "p1");
        assertThat(loaded.causalStatus()).isEqualTo(CausalStatus.EXTRACTED);
        assertThat(loaded.causalClaimCount()).isEqualTo(3);

        var plainFilter = new PaperFilter(null, null, null, null, null, false);
        store.count(plainFilter);
        verify(mapper).countWithClaims(plainFilter, CausalClaimSnapshot.EMPTY);

        var causalFilter = new PaperFilter(null, null, null, null, null, CausalStatus.EXTRACTED, false);
        store.count(causalFilter);
        ArgumentCaptor<CausalClaimSnapshot> captor = ArgumentCaptor.forClass(CausalClaimSnapshot.class);
        verify(mapper).countWithClaims(eq(causalFilter), captor.capture());
        assertThat(captor.getValue().workIds()).containsExactly("W1", "W3");
    }

    @Test
    void remoteJournalStorePassesClaimSnapshotToMapper() {
        CausalFactReader facts = mock(CausalFactReader.class);
        JournalMapper mapper = mock(JournalMapper.class);
        when(facts.listPaperClaimCounts()).thenReturn(List.of(new NamedCountRow("W9", 4L)));

        var store = new RemoteCausalJournalStore(mapper, facts);
        store.listAll("dataset_20241125");
        store.years("S123");

        ArgumentCaptor<CausalClaimSnapshot> captor = ArgumentCaptor.forClass(CausalClaimSnapshot.class);
        verify(mapper).listAllWithClaims(eq("dataset_20241125"), captor.capture());
        assertThat(captor.getValue().workIds()).containsExactly("W9");
        assertThat(captor.getValue().counts()).containsExactly(4L);
        verify(mapper).years("S123");
        verify(mapper, never()).listAll(any());
    }
}
