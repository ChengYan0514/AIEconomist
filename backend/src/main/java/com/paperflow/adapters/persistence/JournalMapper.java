package com.paperflow.adapters.persistence;

import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface JournalMapper extends JournalStore {
    List<JournalSummary> listAllWithClaims(@Param("schema") String schema,
                                           @Param("claims") CausalClaimSnapshot claims);

    JournalSummary getWithClaims(@Param("schema") String schema, @Param("sourceId") String sourceId,
                                 @Param("claims") CausalClaimSnapshot claims);

    @Override
    default List<JournalSummary> listAll(String schema) {
        return listAllWithClaims(schema, null);
    }

    @Override
    default JournalSummary get(String schema, String sourceId) {
        return getWithClaims(schema, sourceId, null);
    }

    @Override
    long importedWorksCount(@Param("sourceId") String sourceId);

    @Override
    List<JournalYearStats> years(@Param("sourceId") String sourceId);
}
