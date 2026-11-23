package com.paperflow.paper.journal;

public record JournalFilter(String q, Mode mode, Sort sort) {
    public enum Mode { ALL, HAS_FAILED, HAS_PENDING, ALL_PUBLISHED }

    public enum Sort { PAPERS_DESC, PUBLISHED_RATE_DESC, FAILED_DESC, PENDING_DESC, UPDATED_DESC, NAME_ASC }

    public JournalFilter {
        q = q == null || q.isBlank() ? null : q.strip();
        if (q != null && q.length() > 200) throw new IllegalArgumentException("搜索词最多 200 个字符");
        mode = mode == null ? Mode.ALL : mode;
        sort = sort == null ? Sort.PAPERS_DESC : sort;
    }
}
