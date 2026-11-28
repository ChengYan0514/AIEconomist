package com.paperflow.paper.journal;

import java.util.Comparator;
import java.util.Locale;

public record JournalFilter(String q, Mode mode, Sort sort) {
    public enum Mode { ALL, HAS_FAILED, HAS_PENDING, ALL_PUBLISHED }

    public enum Sort { PAPERS_DESC, PUBLISHED_RATE_DESC, CAUSAL_RATE_DESC, FAILED_DESC, PENDING_DESC, UPDATED_DESC, NAME_ASC }

    public JournalFilter {
        q = q == null || q.isBlank() ? null : q.strip();
        if (q != null && q.length() > 200) throw new IllegalArgumentException("搜索词最多 200 个字符");
        mode = mode == null ? Mode.ALL : mode;
        sort = sort == null ? Sort.PAPERS_DESC : sort;
    }

    public boolean matches(JournalSummary journal) {
        if (q != null) {
            String needle = q.toLowerCase(Locale.ROOT);
            String name = journal.name() == null ? "" : journal.name().toLowerCase(Locale.ROOT);
            String sourceId = journal.sourceId() == null ? "" : journal.sourceId().toLowerCase(Locale.ROOT);
            if (!name.contains(needle) && !sourceId.contains(needle)) return false;
        }
        return switch (mode) {
            case ALL -> true;
            case HAS_FAILED -> journal.failedCount() > 0;
            case HAS_PENDING -> journal.unprocessedCount() > 0 || journal.readyCount() > 0;
            case ALL_PUBLISHED -> journal.totalPapers() > 0 && journal.publishedCount() == journal.totalPapers();
        };
    }

    public Comparator<JournalSummary> comparator() {
        Comparator<JournalSummary> byId = Comparator.comparing(JournalSummary::sourceId, Comparator.nullsLast(String::compareTo));
        Comparator<JournalSummary> byPapers = Comparator.comparingLong(JournalSummary::totalPapers).reversed();
        return switch (sort) {
            case PUBLISHED_RATE_DESC -> Comparator
                    .comparingDouble((JournalSummary j) -> (double) j.publishedCount() / Math.max(j.totalPapers(), 1))
                    .reversed().thenComparing(byPapers).thenComparing(byId);
            case CAUSAL_RATE_DESC -> Comparator
                    .comparingDouble((JournalSummary j) -> (double) j.causalCompletedCount() / Math.max(j.totalPapers(), 1))
                    .reversed()
                    .thenComparing(Comparator.comparingLong(JournalSummary::causalCompletedCount).reversed())
                    .thenComparing(byPapers).thenComparing(byId);
            case FAILED_DESC -> Comparator.comparingLong(JournalSummary::failedCount).reversed()
                    .thenComparing(byPapers).thenComparing(byId);
            case PENDING_DESC -> Comparator.comparingLong((JournalSummary j) -> j.unprocessedCount() + j.readyCount())
                    .reversed().thenComparing(byPapers).thenComparing(byId);
            case UPDATED_DESC -> Comparator.comparing(JournalSummary::latestUpdatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(byPapers).thenComparing(byId);
            case NAME_ASC -> Comparator.comparing(
                    (JournalSummary j) -> j.name() == null ? "" : j.name().toLowerCase(Locale.ROOT))
                    .thenComparing(byId);
            case PAPERS_DESC -> byPapers
                    .thenComparing(Comparator.comparingLong(JournalSummary::publishedCount).reversed())
                    .thenComparing(byId);
        };
    }
}
