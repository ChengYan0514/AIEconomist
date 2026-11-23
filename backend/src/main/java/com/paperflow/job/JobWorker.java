package com.paperflow.job;

import com.paperflow.fulltext.FullTextService;
import com.paperflow.fulltext.ParseTask;
import com.paperflow.openalex.OpenAlexService;
import com.paperflow.original.ImportRow;
import com.paperflow.original.OriginalImports;
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

    public JobWorker(JobStore jobs, JobJson json, OriginalImports originals, FullTextService text, OpenAlexService openAlex,
                     TransactionTemplate transactions, Duration timeout) {
        this.jobs = jobs;
        this.json = json;
        this.originals = originals;
        this.text = text;
        this.openAlex = openAlex;
        this.transactions = transactions;
        this.leaseSeconds = timeout.plusMinutes(5).toSeconds();
    }

    public void drain() {
        jobs.expireClaims();
        for (int count = 0; count < 40 && !Thread.currentThread().isInterrupted(); count++) {
            var item = transactions.execute(status -> jobs.claim(leaseSeconds));
            if (item == null) return;
            run(item);
        }
    }

    private void run(JobItem item) {
        try {
            Runnable claim = () -> {
                if (!jobs.claimActive(item.jobId(), item.sequence())) throw new JobConflict("任务执行时间已到期");
            };
            claim.run();
            Outcome result = execute(item, claim);
            jobs.finish(item.jobId(), item.sequence(), result.state(), result.message(), result.data());
        } catch (InterruptedException error) {
            failed(item, "任务执行中断，可重试");
            Thread.currentThread().interrupt();
        } catch (NoSuchFileException error) {
            failed(item, "登记的原件或解析产物不可读取");
        } catch (IllegalArgumentException | PaperConflict | PaperNotFound | JobConflict error) {
            failed(item, error.getMessage());
        } catch (IOException error) {
            log.warn("File stage {} failed for job {} item {}", item.kind(), item.jobId(), item.sequence(), error);
            failed(item, item.kind() == JobKind.PARSE ? "MinerU 解析失败，请检查原件和解析服务" : "文件读取或处理失败");
        } catch (RuntimeException error) {
            log.error("Job {} item {} failed", item.jobId(), item.sequence(), error);
            failed(item, "执行失败，可查看服务日志或重试");
        }
    }

    private Outcome execute(JobItem item, Runnable claim) throws IOException, InterruptedException {
        return switch (item.kind()) {
            case ORIGINAL_IMPORT -> {
                String paperId = originals.apply(json.read(item.input(), ImportRow.class));
                yield paperId == null ? skip("论文已存在") :
                        new Outcome("SUCCEEDED", "原件已登记", json.write(Map.of("paperId", paperId)));
            }
            case PARSE -> {
                ParseTask previous = item.result() == null ? null : json.read(item.result(), ParseTask.class);
                text.parse(item.paperId(), item.fileRevision(), previous, task -> {
                    if (jobs.checkpoint(item.jobId(), item.sequence(), json.write(task)) != 1) {
                        throw new JobConflict("任务执行时间已到期");
                    }
                }, claim);
                yield success("解析已完成");
            }
            case PUBLISH -> {
                String target = json.tree(item.input()).path("parseId").asText(null);
                boolean published = text.publish(item.paperId(), item.fileRevision(), target == null ? null : UUID.fromString(target), claim);
                yield published ? success("全文已发布") : skip("该解析产物已发布");
            }
            case MATCH -> {
                var result = openAlex.match(item.paperId(), item.recordVersion(), claim);
                yield new Outcome(result.matched() ? "SUCCEEDED" : "SKIPPED", result.message(), json.write(result));
            }
            case METADATA -> {
                JobRequest request = json.read(item.input(), JobRequest.class);
                long imported = openAlex.importWorks(request.sourceId(), request.yearFrom(), request.yearTo(), claim);
                yield new Outcome("SUCCEEDED", "已导入 " + imported + " 条作品元数据", json.write(Map.of("imported", imported)));
            }
        };
    }

    private void failed(JobItem item, String message) {
        jobs.finish(item.jobId(), item.sequence(), "FAILED", message, null);
    }

    private static Outcome success(String message) { return new Outcome("SUCCEEDED", message, null); }
    private static Outcome skip(String message) { return new Outcome("SKIPPED", message, null); }

    private record Outcome(String state, String message, String data) {}
}
