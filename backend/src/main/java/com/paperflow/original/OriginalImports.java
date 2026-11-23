package com.paperflow.original;

import com.paperflow.job.*;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.PaperConflict;
import com.paperflow.paper.model.Paper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Consumer;

public final class OriginalImports {
    public static final String PREFLIGHT_PATH = "preflightPath";

    private final OriginalArchive archive;
    private final JobService jobs;
    private final JobJson json;
    private final PaperService papers;

    public OriginalImports(OriginalArchive archive, JobService jobs, JobJson json, PaperService papers) {
        this.archive = archive;
        this.jobs = jobs;
        this.json = json;
        this.papers = papers;
    }

    public JobSummary preflight(String username, String filename, InputStream content) throws IOException {
        UUID id = UUID.randomUUID();
        try {
            var rows = archive.preflight(id, content);
            var items = new ArrayList<JobItem>(rows.size());
            var seen = new HashSet<String>();
            for (var row : rows) items.add(item(id, row, seen));
            return jobs.preflight(id, username, filename == null ? "batch.zip" : filename, items);
        } catch (RuntimeException error) {
            archive.discard(id);
            throw error;
        }
    }

    public String apply(ImportRow row, Consumer<Paper> afterCreate) throws IOException {
        try {
            return archive.read(row, input -> papers.create(row.metadata(), input, row.type(), afterCreate).paperId());
        } catch (PaperConflict error) {
            if (!"DUPLICATE_PAPER".equals(error.code())) throw error;
            return null;
        }
    }

    private JobItem item(UUID id, OriginalArchive.Row row, HashSet<String> seen) {
        String title = row.title() == null || row.title().isBlank() ? "第 " + row.sequence() + " 行" : row.title();
        if (row.error() != null) return row(id, row, title, null, "{}", "FAILED", row.error(), null);
        var metadata = row.metadata();
        try {
            papers.validateSource(metadata.sourceId());
            String duplicate = papers.duplicate(metadata);
            if (duplicate != null) return row(id, row, title, duplicate, "{}", "SKIPPED", "论文已存在", duplicate);
            String paperId = metadata.paperId();
            if (!seen.add(paperId)) return row(id, row, title, null, "{}", "SKIPPED", "与包内其他行重复", null);
            String input = json.write(new ImportRow(id.toString(), row.file(), row.type(), metadata));
            return row(id, row, title, paperId, input, "READY", null, null);
        } catch (IllegalArgumentException error) {
            return row(id, row, title, null, "{}", "FAILED", error.getMessage(), null);
        }
    }

    private JobItem row(UUID id, OriginalArchive.Row row, String title, String paperId, String input,
                        String state, String message, String existing) {
        var result = new HashMap<String, String>();
        if (existing != null) result.put("paperId", existing);
        String path = row.file();
        if ("{}".equals(input) && path != null && !path.isBlank()) {
            // PostgreSQL JSONB cannot store NUL. Keep invalid paths readable in the review.
            result.put(PREFLIGHT_PATH, path.replace("\0", "\\0"));
        }
        return new JobItem(id, JobKind.ORIGINAL_IMPORT, row.sequence(), title, paperId, null, null, input, state,
                message, result.isEmpty() ? null : json.write(result));
    }
}
