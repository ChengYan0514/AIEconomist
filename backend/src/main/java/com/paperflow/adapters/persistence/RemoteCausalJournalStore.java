package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;

import java.util.List;

public final class RemoteCausalJournalStore implements JournalStore {
    private final JournalMapper mapper;
    private final CausalFactReader causalFacts;

    public RemoteCausalJournalStore(JournalMapper mapper, CausalFactReader causalFacts) {
        this.mapper = mapper;
        this.causalFacts = causalFacts;
    }

    @Override
    public List<JournalSummary> listAll(String schema) {
        return mapper.listAllWithClaims(schema, CausalClaimSnapshot.from(causalFacts));
    }

    @Override
    public JournalSummary get(String schema, String sourceId) {
        return mapper.getWithClaims(schema, sourceId, CausalClaimSnapshot.from(causalFacts));
    }

    @Override
    public long importedWorksCount(String sourceId) {
        return mapper.importedWorksCount(sourceId);
    }

    @Override
    public List<JournalYearStats> years(String sourceId) {
        return mapper.years(sourceId);
    }
}
