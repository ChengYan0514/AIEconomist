package com.paperflow.job;

import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobService {
    public static final int MAX_TARGETS = 10000;
    private final JobStore jobs;
    private final PaperService papers;
    private final JobJson json;
    private final TransactionTemplate transactions;

    public JobService(JobStore jobs, PaperService papers, JobJson json, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.papers = papers;
        this.json = json;
        this.transactions = transactions;
    }

    public JobSummary start(JobRequest request, String username) {
        return transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            List<JobItem> inputs = prepare(id, request);
            if (inputs.isEmpty()) throw new JobConflict("当前范围没有可处理的论文");
            var scope = request.paperIds().isEmpty() ? request : Map.of("selectedCount", request.paperIds().size());
            jobs.create(id, request.kind(), username, json.write(scope), null);
            addItems(inputs);
            return get(id);
        });
    }

    public JobSummary preflight(UUID id, String username, String filename, List<JobItem> inputs) {
        return transactions.execute(status -> {
            jobs.create(id, JobKind.ORIGINAL_IMPORT, username, json.write(Map.of("filename", filename)), null);
            addItems(inputs);
            return get(id);
        });
    }

    public JobSummary confirm(UUID id) {
        return transactions.execute(status -> {
            JobSummary job = get(id);
            if (job.kind() != JobKind.ORIGINAL_IMPORT) throw new JobConflict("此任务无需确认");
            if (job.confirmedAt() != null) return job;
            if (job.ready() == 0) throw new JobConflict("没有通过预检的记录");
            if (jobs.confirm(id) == 1) jobs.queueReady(id);
            return get(id);
        });
    }

    public JobSummary retry(UUID previous, String username) {
        return transactions.execute(status -> {
            JobSummary old = get(previous);
            if (!old.finishedWithFailures()) {
                throw new JobConflict("只有已完成任务的失败项可重试");
            }
            UUID id = UUID.randomUUID();
            jobs.create(id, old.kind(), username, old.scope(), previous);
            if (old.kind() == JobKind.ORIGINAL_IMPORT) jobs.confirm(id);
            if (jobs.retryFailed(id, previous) == 0) throw new JobConflict("预检失败项需要修正后重新上传");
            return get(id);
        });
    }

    public JobSummary get(UUID id) {
        JobSummary job = jobs.get(id);
        if (job == null) throw new JobNotFound();
        return job;
    }

    public List<JobSummary> list(JobKind kind, String state, int size, long offset) {
        return jobs.list(kind, state, size, offset);
    }

    public long count(JobKind kind, String state) {
        return jobs.count(kind, state);
    }

    public List<JobItem> items(UUID id, String state, int size, long offset) {
        return jobs.items(id, state, size, offset);
    }

    public long countItems(UUID id, String state) {
        return jobs.countItems(id, state);
    }

    private List<JobItem> prepare(UUID id, JobRequest request) {
        if (request.sourceId() != null) papers.validateSource(request.sourceId());
        if (request.kind() == JobKind.METADATA) {
            return List.of(new JobItem(id, request.kind(), 1, request.sourceId(), null, null, null,
                    json.write(request), "QUEUED", null, null));
        }
        List<String> ids = request.sourceId() == null ? request.paperIds() :
                papers.range(request.sourceId(), request.yearFrom(), request.yearTo(), MAX_TARGETS + 1);
        if (ids.size() > MAX_TARGETS) throw new JobConflict("每次最多处理 10000 篇，请缩小范围");
        var result = new ArrayList<JobItem>(ids.size());
        Map<String, Paper> rows = papers.getMany(ids);
        for (String paperId : ids) {
            Paper paper = rows.get(paperId);
            if (paper == null) throw new com.paperflow.paper.model.PaperNotFound();
            result.add(paperItem(id, request.kind(), result.size() + 1, paper));
        }
        return result;
    }

    private JobItem paperItem(UUID id, JobKind kind, int sequence, Paper paper) {
        String skip = skipped(kind, paper);
        String input = kind == JobKind.PUBLISH && paper.latestParseId() != null
                ? json.write(Map.of("parseId", paper.latestParseId())) : "{}";
        return new JobItem(id, kind, sequence, paper.title() == null ? paper.paperId() : paper.title(), paper.paperId(),
                paper.revision(), paper.version(), input, skip == null ? "QUEUED" : "SKIPPED", skip, null);
    }

    private String skipped(JobKind kind, Paper paper) {
        if (paper.archived()) return "论文已归档";
        if (kind == JobKind.PARSE && !"PDF".equals(paper.originalType())) return "当前原件格式暂不支持解析";
        if (kind == JobKind.MATCH && paper.matchedWorkId() != null) return "已有关联";
        if (kind == JobKind.PUBLISH && paper.latestParseId() == null && paper.status() != PaperStatus.READY) {
            return paper.status() == PaperStatus.PUBLISHED ? "已有发布全文" : "请先生成解析产物";
        }
        return null;
    }

    private void addItems(List<JobItem> inputs) {
        for (int start = 0; start < inputs.size(); start += 500) {
            jobs.addItems(inputs.subList(start, Math.min(start + 500, inputs.size())));
        }
    }
}
