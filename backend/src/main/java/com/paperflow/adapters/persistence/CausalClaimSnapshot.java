package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader;

import java.util.List;

public record CausalClaimSnapshot(String[] workIds, Long[] counts) {
    public static final CausalClaimSnapshot EMPTY = new CausalClaimSnapshot(new String[0], new Long[0]);

    public CausalClaimSnapshot {
        workIds = workIds == null ? new String[0] : workIds;
        counts = counts == null ? new Long[0] : counts;
        if (workIds.length != counts.length) {
            throw new IllegalArgumentException("workIds and counts length mismatch");
        }
    }

    public static CausalClaimSnapshot from(CausalFactReader reader) {
        List<CausalFactReader.NamedCountRow> rows = reader.listPaperClaimCounts();
        if (rows == null || rows.isEmpty()) return EMPTY;
        String[] ids = new String[rows.size()];
        Long[] counts = new Long[rows.size()];
        int size = 0;
        for (var row : rows) {
            if (row.name() == null || row.name().isBlank() || row.count() <= 0) continue;
            ids[size] = row.name();
            counts[size] = row.count();
            size++;
        }
        if (size == 0) return EMPTY;
        if (size == rows.size()) return new CausalClaimSnapshot(ids, counts);
        String[] trimmedIds = new String[size];
        Long[] trimmedCounts = new Long[size];
        System.arraycopy(ids, 0, trimmedIds, 0, size);
        System.arraycopy(counts, 0, trimmedCounts, 0, size);
        return new CausalClaimSnapshot(trimmedIds, trimmedCounts);
    }
}
