package com.paperflow.paper.journal;

import java.util.List;

public record JournalDetail(
        JournalSummary journal,
        long importedWorksCount,
        List<JournalYearStats> years
) {}
