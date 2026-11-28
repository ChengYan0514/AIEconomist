package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.paper.manage.PaperStore;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperMetadata;
import com.paperflow.paper.model.StoredOriginal;

import java.util.List;
import java.util.function.Consumer;

public final class RemoteCausalPaperStore implements PaperStore {
    private final PaperMapper mapper;
    private final CausalFactReader causalFacts;

    public RemoteCausalPaperStore(PaperMapper mapper, CausalFactReader causalFacts) {
        this.mapper = mapper;
        this.causalFacts = causalFacts;
    }

    @Override
    public Paper get(String schema, String id) {
        Paper paper = mapper.getWithClaims(schema, id, CausalClaimSnapshot.EMPTY);
        if (paper == null || paper.matchedWorkId() == null || paper.matchedWorkId().isBlank()) return paper;
        var summary = causalFacts.findPaperSummary(paper.matchedWorkId());
        int claims = summary == null ? 0 : Math.toIntExact(summary.claimRecordCount());
        return paper.withCausalClaims(claims);
    }

    @Override
    public List<Paper> getMany(String schema, List<String> ids) {
        return mapper.getManyWithClaims(schema, ids, CausalClaimSnapshot.from(causalFacts));
    }

    @Override
    public List<Paper> list(String schema, PaperFilter filter, long offset, int size) {
        return mapper.listWithClaims(schema, filter, offset, size, CausalClaimSnapshot.from(causalFacts));
    }

    @Override
    public long export(String schema, PaperFilter filter, Consumer<Paper> output) {
        CausalClaimSnapshot claims = filter.causalStatus() == null
                ? CausalClaimSnapshot.EMPTY
                : CausalClaimSnapshot.from(causalFacts);
        return mapper.exportWithClaims(schema, filter, claims, output);
    }

    @Override
    public long count(PaperFilter filter) {
        CausalClaimSnapshot claims = filter.causalStatus() == null
                ? CausalClaimSnapshot.EMPTY
                : CausalClaimSnapshot.from(causalFacts);
        return mapper.countWithClaims(filter, claims);
    }

    @Override public List<IdentityRow> atSourceYear(String sourceId, int year) { return mapper.atSourceYear(sourceId, year); }
    @Override public List<String> range(String sourceId, Integer yearFrom, Integer yearTo, int limit) { return mapper.range(sourceId, yearFrom, yearTo, limit); }
    @Override public boolean sourceExists(String schema, String sourceId) { return mapper.sourceExists(schema, sourceId); }
    @Override public int add(String id, PaperMetadata data, StoredOriginal file) { return mapper.add(id, data, file); }
    @Override public int lock(String id) { return mapper.lock(id); }
    @Override public int edit(String id, PaperMetadata data) { return mapper.edit(id, data); }
    @Override public int archive(String id, boolean archived) { return mapper.archive(id, archived); }
    @Override public int replace(String id, StoredOriginal file) { return mapper.replace(id, file); }
}
