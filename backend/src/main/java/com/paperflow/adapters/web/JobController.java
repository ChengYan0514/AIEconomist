package com.paperflow.adapters.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.paperflow.account.model.AccountAccess;
import com.paperflow.job.*;
import com.paperflow.original.OriginalImports;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.paperflow.adapters.web.WorkspaceAccess.CURRENT_ACCOUNT;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobService jobs;
    private final OriginalImports originals;
    private final JobJson json;
    private final WorkspaceAccess access;

    public JobController(JobService jobs, OriginalImports originals, JobJson json, WorkspaceAccess access) {
        this.jobs = jobs;
        this.originals = originals;
        this.json = json;
        this.access = access;
    }

    record StartJob(JobKind kind, java.util.List<String> paperIds, String sourceId, Integer yearFrom, Integer yearTo) {}
    record JobView(UUID jobId, JobKind kind, String state, String username, UUID retryOf, int total, int ready,
                   int queued, int running, int succeeded, int skipped, int failed, OffsetDateTime createdAt,
                   OffsetDateTime finishedAt, OffsetDateTime confirmedAt, JsonNode scope, boolean canRetry) {}
    record ItemView(int sequence, String target, String paperId, String state, String message, JsonNode result,
                    boolean inputError) {}

    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView preflight(@RequestParam MultipartFile file, Principal actor) throws IOException {
        try (var stream = file.getInputStream()) {
            return view(originals.preflight(actor.getName(), file.getOriginalFilename(), stream));
        }
    }

    @PostMapping("/imports/{jobId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView confirm(@PathVariable UUID jobId) {
        return view(jobs.confirm(jobId));
    }

    @GetMapping("/jobs")
    Page<JobView> list(@RequestParam(required = false) JobKind kind, @RequestParam(required = false) String state,
                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var rows = jobs.list(kind, state, size, Page.offset(page, size)).stream().map(this::view).toList();
        return new Page<>(rows, page, size, jobs.count(kind, state));
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView start(@RequestBody StartJob request, @RequestAttribute(CURRENT_ACCOUNT) AccountAccess actor) {
        access.requireJob(actor, request.kind(), "JOB_SUBMIT", String.valueOf(request.kind()));
        return view(jobs.start(new JobRequest(request.kind(), request.paperIds(), request.sourceId(),
                request.yearFrom(), request.yearTo()), actor.username()));
    }

    @GetMapping("/jobs/{jobId}")
    JobView detail(@PathVariable UUID jobId) {
        return view(jobs.get(jobId));
    }

    @GetMapping("/jobs/{jobId}/items")
    Page<ItemView> items(@PathVariable UUID jobId, @RequestParam(required = false) String state,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var job = jobs.get(jobId);
        var items = jobs.items(jobId, state, size, Page.offset(page, size)).stream()
                .map(item -> new ItemView(item.sequence(), item.target(), item.paperId(), item.state(), item.message(),
                        json.tree(item.result()), item.inputError())).toList();
        long total = state == null || state.isEmpty() ? job.total() : jobs.countItems(jobId, state);
        return new Page<>(items, page, size, total);
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView retry(@PathVariable UUID jobId, @RequestAttribute(CURRENT_ACCOUNT) AccountAccess actor) {
        access.requireJob(actor, jobs.get(jobId).kind(), "JOB_RETRY", jobId.toString());
        return view(jobs.retry(jobId, actor.username()));
    }

    private JobView view(JobSummary job) {
        return new JobView(job.jobId(), job.kind(), job.state(), job.username(), job.retryOf(), job.total(),
                job.ready(), job.queued(), job.running(), job.succeeded(), job.skipped(), job.failed(), job.createdAt(),
                job.finishedAt(), job.confirmedAt(), json.tree(job.scope()), job.canRetry());
    }
}
