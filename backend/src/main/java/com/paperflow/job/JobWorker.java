package com.paperflow.job;

import com.paperflow.fulltext.FullTextService;
import com.paperflow.fulltext.ParseTask;
import com.paperflow.openalex.OpenAlexService;
import com.paperflow.original.ImportRow;
import com.paperflow.original.OriginalImports;
import com.paperflow.paper.model.DuplicatePaper;
import com.paperflow.paper.model.PaperConflict;
import com.paperflow.paper.model.PaperNotFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public final class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final JobStore jobs;
    private final JobJson json;
    private final OriginalImports originals;
    private final FullTextService text;
    private final OpenAlexService openAlex;
    private final TransactionTemplate transactions;
    private final long leaseSeconds;
    private final JobAudit audit;

    public JobWorker(JobStore jobs, JobJson json, OriginalImports originals, FullTextService text, OpenAlexService openAlex,
                     TransactionTemplate transactions, Duration timeout, JobAudit audit) {
        this.jobs = jobs;
        this.json = json;
        this.originals = originals;
        this.text = text;
        this.openAlex = openAlex;
        this.transactions = transactions;
        this.audit = audit;
        this.leaseSeconds = timeout.plusMinutes(5).toSeconds();
    }

    public void drain() {
        expireClaims();
        for (int count = 0; count < 40 && !Thread.currentThread().isInterrupted(); count++) {
            var item = transactions.execute(status -> jobs.claim(leaseSeconds));
            if (item == null) return;
            run(item);
        }
    }

    private void run(JobItem item) {
        String actor = jobs.username(item.jobId());
        Runnable claim = () -> {
            if (!jobs.claimActive(item.jobId(), item.sequence())) throw expired();
        };
        try {
            claim.run();
            execute(item, actor, claim);
        } catch (InterruptedException error) {
            failed(item, actor, "INTERRUPTED", "任务执行中断，可重试");
            Thread.currentThread().interrupt();
        } catch (NoSuchFileException error) {
            failed(item, actor, "FILE_MISSING", "登记的原件或解析产物不可读取");
        } catch (PaperConflict error) {
            failed(item, actor, error.code(), error.getMessage());
        } catch (PaperNotFound error) {
            failed(item, actor, "PAPER_NOT_FOUND", error.getMessage());
        } catch (JobConflict error) {
            failed(item, actor, "CLAIM_EXPIRED", error.getMessage());
        } catch (IllegalArgumentException error) {
            failed(item, actor, "INVALID_INPUT", error.getMessage());
        } catch (IOException error) {
            log.warn("File stage {} failed for job {} item {}", item.kind(), item.jobId(), item.sequence(), error);
            if (item.kind() == JobKind.PARSE) {
                failed(item, actor, "PARSE_FAILED", "MinerU 解析失败，请检查原件和解析服务");
            } else {
                failed(item, actor, "FILE_PROCESSING_FAILED", "文件读取或处理失败");
            }
        } catch (RuntimeException error) {
            log.error("Job {} item {} failed", item.jobId(), item.sequence(), error);
            failed(item, actor, "EXECUTION_FAILED", "执行失败，可查看服务日志或重试");
        }
    }

    private void execute(JobItem item, String actor, Runnable claim) throws IOException, InterruptedException {
        switch (item.kind()) {
            case ORIGINAL_IMPORT -> importOriginal(item, actor);
            case PARSE -> parse(item, actor, claim);
            case PUBLISH -> publish(item, actor, claim);
            case MATCH -> match(item, actor, claim);
            case METADATA -> {
                JobRequest request = json.read(item.input(), JobRequest.class);
                long imported = openAlex.importWorks(request.sourceId(), request.yearFrom(), request.yearTo(), claim);
                completed(item, actor, new Outcome("SUCCEEDED", "已导入 " + imported + " 条作品元数据",
                        json.write(Map.of("imported", imported)), null, null));
            }
        }
    }

    private void importOriginal(JobItem item, String actor) throws IOException {
        try {
            originals.apply(json.read(item.input(), ImportRow.class), paper ->
                    completed(item, actor, new Outcome("SUCCEEDED", "原件已登记",
                            json.write(Map.of("paperId", paper.paperId())), null, paper.revision())));
        } catch (DuplicatePaper duplicate) {
            String paperId = duplicate.paperId();
            completed(item.withPaperId(paperId), actor, new Outcome("SKIPPED", "论文已存在",
                    json.write(Map.of("paperId", paperId)), duplicate.code(), null));
        }
    }

    private void parse(JobItem item, String actor, Runnable claim) throws IOException, InterruptedException {
        ParseTask previous = item.result() == null ? null : json.read(item.result(), ParseTask.class);
        text.parse(item.paperId(), item.fileRevision(), previous, task -> {
            if (jobs.checkpoint(item.jobId(), item.sequence(), json.write(task)) != 1) throw expired();
        }, claim, () -> completed(item, actor, new Outcome("SUCCEEDED", "解析已完成", null, null, item.fileRevision())));
    }

    private void publish(JobItem item, String actor, Runnable claim) throws IOException {
        String target = json.tree(item.input()).path("parseId").asText(null);
        boolean published = text.publish(item.paperId(), item.fileRevision(), target == null ? null : UUID.fromString(target),
                claim, () -> completed(item, actor, new Outcome("SUCCEEDED", "全文已发布", null, null, item.fileRevision())));
        if (!published) {
            completed(item, actor, new Outcome("SKIPPED", "该解析产物已发布", null, "ALREADY_PUBLISHED", item.fileRevision()));
        }
    }

    private void match(JobItem item, String actor, Runnable claim) {
        var result = openAlex.match(item.paperId(), item.recordVersion(), claim, matched ->
                completed(item, actor, new Outcome("SUCCEEDED", matched.message(), json.write(matched), null, null)));
        if (!result.matched()) {
            completed(item, actor, new Outcome("SKIPPED", result.message(), json.write(result), null, null));
        }
    }

    private void completed(JobItem item, String actor, Outcome outcome) {
        if (!finish(item, actor, outcome)) throw expired();
    }

    private void failed(JobItem item, String actor, String code, String message) {
        finish(item, actor, new Outcome("FAILED", message, null, code, item.fileRevision()));
    }

    private boolean finish(JobItem item, String actor, Outcome outcome) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (jobs.finish(item.jobId(), item.sequence(), item.paperId(), outcome.state(), outcome.message(), outcome.data()) != 1) {
                return false;
            }
            audit.executed(actor, item, outcome.state(), outcome.code(), outcome.revision());
            return true;
        }));
    }

    private void expireClaims() {
        transactions.executeWithoutResult(status -> jobs.expireClaims().forEach(item ->
                audit.executed(jobs.username(item.jobId()), item, "FAILED", "CLAIM_EXPIRED", item.fileRevision())));
    }

    private JobConflict expired() {
        return new JobConflict("任务执行时间已到期");
    }

    private record Outcome(String state, String message, String data, String code, Long revision) {}
}
