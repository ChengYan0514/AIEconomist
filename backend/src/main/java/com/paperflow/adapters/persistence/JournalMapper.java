package com.paperflow.adapters.persistence;

import com.paperflow.paper.journal.CorpusJournalOverview;
import com.paperflow.paper.journal.JournalFilter;
import com.paperflow.paper.journal.JournalStore;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface JournalMapper extends JournalStore {
    @Override
    CorpusJournalOverview overview(@Param("schema") String schema);

    @Override
    List<JournalSummary> list(@Param("schema") String schema, @Param("filter") JournalFilter filter,
                              @Param("offset") long offset, @Param("size") int size);

    @Override
    long count(@Param("schema") String schema, @Param("filter") JournalFilter filter);

    @Override
    JournalSummary get(@Param("schema") String schema, @Param("sourceId") String sourceId);

    @Override
    long importedWorksCount(@Param("sourceId") String sourceId);

    @Override
    List<JournalYearStats> years(@Param("sourceId") String sourceId);
}
