package com.paperflow.paper.journal;

public record JournalYearStats(
        int year,
        long total,
        long published,
        long ready,
        long unprocessed,
        long failed,
        long unsupported,
        long matched
) {}
