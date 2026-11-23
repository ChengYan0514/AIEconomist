package com.paperflow.job;

import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperMetadata;
import java.util.List;

public record JobRequest(JobKind kind, List<String> paperIds, String sourceId, Integer yearFrom, Integer yearTo) {
    public JobRequest {
        if (kind == null || kind == JobKind.ORIGINAL_IMPORT) throw new IllegalArgumentException("请选择处理阶段");
        List<String> ids = paperIds == null ? List.of() : paperIds;
        if (ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)) {
            throw new IllegalArgumentException("包含无效的论文 ID");
        }
        paperIds = ids.stream().distinct().toList();
        sourceId = sourceId == null || sourceId.isBlank() ? null : PaperMetadata.source(sourceId);
        PaperFilter.validateYears(yearFrom, yearTo);
        if (paperIds.isEmpty() == (sourceId == null) || (kind == JobKind.METADATA && sourceId == null)) {
            throw new IllegalArgumentException("请选择论文或一个期刊作为处理范围");
        }
        if (sourceId == null && (yearFrom != null || yearTo != null)) {
            throw new IllegalArgumentException("按期刊处理时可指定年份范围");
        }
    }
}
