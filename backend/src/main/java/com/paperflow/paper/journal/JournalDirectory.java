package com.paperflow.paper.journal;

import java.util.List;

public record JournalDirectory(
        List<JournalSummary> items,
        long total,
        CorpusJournalOverview overview
) {}
