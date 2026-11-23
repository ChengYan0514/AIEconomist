package com.paperflow.adapters.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final AuditAttempt audit;

    public JobController(JobService jobs, OriginalImports originals, JobJson json, WorkspaceAccess access, AuditAttempt audit) {
        this.jobs = jobs;
        this.originals = originals;
        this.json = json;
        this.access = access;
        this.audit = audit;
    }

    record StartJob(JobKind kind, java.util.List<String> paperIds, String sourceId, Integer yearFrom, Integer yearTo) {}
    record JobView(UUID jobId, JobKind kind, String state, String username, UUID retryOf, int total, int ready,
                   int queued, int running, int succeeded, int skipped, int failed, OffsetDateTime createdAt,
                   OffsetDateTime finishedAt, OffsetDateTime confirmedAt, JsonNode scope, boolean canRetry) {}
    record ItemView(int sequence, String target, String paperId, String state, String message, JsonNode result,
                    boolean inputError, PreflightView preflight) {}
    record PreflightView(String status, String originalPath, String reason, String existingPaperId) {}

    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView preflight(@RequestParam MultipartFile file, Principal actor) throws IOException {
        try (var stream = file.getInputStream()) {
            return view(audit.run(actor.getName(), "JOB_PREFLIGHT", "JOB_COLLECTION", "jobs",
                    () -> originals.preflight(actor.getName(), file.getOriginalFilename(), stream)));
        }
    }

    @PostMapping("/imports/{jobId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView confirm(@PathVariable UUID jobId, Principal actor) {
        return view(audit.run(actor.getName(), "JOB_SUBMIT", "JOB", jobId.toString(),
                () -> jobs.confirm(jobId, actor.getName())));
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
        return view(audit.run(actor.username(), "JOB_SUBMIT", "JOB_COLLECTION", "jobs", () -> {
            access.requireJob(actor, request.kind(), "JOB_SUBMIT", String.valueOf(request.kind()));
            return jobs.start(new JobRequest(request.kind(), request.paperIds(), request.sourceId(),
                    request.yearFrom(), request.yearTo()), actor.username());
        }));
    }

    @GetMapping("/jobs/{jobId}")
    JobView detail(@PathVariable UUID jobId) {
        return view(jobs.get(jobId));
    }

    @GetMapping("/jobs/{jobId}/items")
    Page<ItemView> items(@PathVariable UUID jobId, @RequestParam(required = false) String state,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var job = jobs.get(jobId);
        var items = jobs.items(jobId, state, size, Page.offset(page, size)).stream().map(this::view).toList();
        long total = state == null || state.isEmpty() ? job.total() : jobs.countItems(jobId, state);
        return new Page<>(items, page, size, total);
    }

    @GetMapping("/papers/{paperId}/jobs")
    Page<PaperJobRecord> paperHistory(@PathVariable String paperId, @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        var history = jobs.paperHistory(paperId, size, Page.offset(page, size));
        return new Page<>(history.items(), page, size, history.total());
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView retry(@PathVariable UUID jobId, @RequestAttribute(CURRENT_ACCOUNT) AccountAccess actor) {
        return view(audit.run(actor.username(), "JOB_RETRY", "JOB", jobId.toString(), () -> {
            access.requireJob(actor, jobs.get(jobId).kind(), "JOB_RETRY", jobId.toString());
            return jobs.retry(jobId, actor.username());
        }));
    }

    private JobView view(JobSummary job) {
        return new JobView(job.jobId(), job.kind(), job.state(), job.username(), job.retryOf(), job.total(),
                job.ready(), job.queued(), job.running(), job.succeeded(), job.skipped(), job.failed(), job.createdAt(),
                job.finishedAt(), job.confirmedAt(), json.tree(job.scope()), job.canRetry());
    }

    private ItemView view(JobItem item) {
        JsonNode result = json.tree(item.result());
        PreflightView preflight = null;
        if (item.kind() == JobKind.ORIGINAL_IMPORT) {
            preflight = preflight(item, result);
            if (result instanceof ObjectNode fields && fields.remove(OriginalImports.PREFLIGHT_PATH) != null && fields.isEmpty()) {
                result = null;
            }
        }
        return new ItemView(item.sequence(), item.target(), item.paperId(), item.state(), item.message(), result,
                item.inputError(), preflight);
    }

    private PreflightView preflight(JobItem item, JsonNode result) {
        if (!"{}".equals(item.input())) {
            return new PreflightView("IMPORTABLE", json.tree(item.input()).path("entry").asText(null), null, null);
        }
        String path = result == null ? null : result.path(OriginalImports.PREFLIGHT_PATH).asText(null);
        String existing = result == null ? null : result.path("paperId").asText(null);
        return new PreflightView(item.inputError() ? "ERROR" : "DUPLICATE", path, item.message(), existing);
    }
}
