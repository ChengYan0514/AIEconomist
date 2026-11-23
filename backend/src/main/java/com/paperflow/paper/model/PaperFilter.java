package com.paperflow.paper.model;

public record PaperFilter(
        String q, String sourceId, Integer yearFrom, Integer yearTo, PaperStatus status, boolean archived
) {
    public PaperFilter {
        q = q == null || q.isBlank() ? null : q.strip();
        if (q != null && q.length() > 200) throw new IllegalArgumentException("搜索词最多 200 个字符");
        sourceId = sourceId == null || sourceId.isBlank() ? null : PaperMetadata.source(sourceId);
        validateYears(yearFrom, yearTo);
    }

    public static void validateYears(Integer from, Integer to) {
        if ((from != null && (from < 1000 || from > 9999)) ||
                (to != null && (to < 1000 || to > 9999)) ||
                (from != null && to != null && from > to)) {
            throw new IllegalArgumentException("请选择有效的年份范围");
        }
    }
}
