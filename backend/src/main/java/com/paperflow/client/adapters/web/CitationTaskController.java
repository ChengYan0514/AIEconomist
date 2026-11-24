package com.paperflow.client.adapters.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.account.model.AccountAccess;
import com.paperflow.client.citation.CitationEventHub;
import com.paperflow.client.citation.CitationTaskService;
import com.paperflow.client.citation.CitationTaskService.ExportFormat;
import com.paperflow.client.citation.CitationTaskStore;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.model.ClientInvalid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.paperflow.adapters.web.WorkspaceAccess.CURRENT_ACCOUNT;

@RestController
@RequestMapping("/api/client/citations/tasks")
public final class CitationTaskController {
    private final CitationTaskService tasks;
    private final CitationTaskRequests requests;
    private final CitationTaskSse sse;

    public CitationTaskController(CitationTaskService tasks, CitationEventHub events, ObjectMapper json) {
        this.tasks = tasks;
        this.requests = new CitationTaskRequests(json);
        this.sse = new CitationTaskSse(tasks, events);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CitationTaskDetailResponse create(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                                             @RequestBody JsonNode body) {
        return CitationTaskDetailResponse.from(tasks.create(account.username(), requests.submission(body)));
    }

    @GetMapping
    public CitationTaskStore.Page list(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                                      @RequestParam(required = false) CitationTaskStatus status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return tasks.list(account.username(), status, page, size);
    }

    @GetMapping("/{taskId}")
    public CitationTaskDetailResponse detail(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                                             @PathVariable UUID taskId) {
        return CitationTaskDetailResponse.from(tasks.detail(account.username(), taskId));
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account, @PathVariable UUID taskId) {
        tasks.delete(account.username(), taskId);
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account, @PathVariable UUID taskId) {
        return sse.open(account.username(), taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public CitationTaskDetailResponse cancel(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                                             @PathVariable UUID taskId) {
        return CitationTaskDetailResponse.from(tasks.cancel(account.username(), taskId));
    }

    @PatchMapping("/{taskId}/review")
    public CitationTaskDetailResponse review(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                                             @PathVariable UUID taskId, @RequestBody JsonNode body) {
        var review = requests.review(body);
        return CitationTaskDetailResponse.from(tasks.review(account.username(), taskId, review.style(), review.selectedReferenceIds()));
    }

    @GetMapping("/{taskId}/export")
    public ResponseEntity<String> export(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account, @PathVariable UUID taskId,
                                         @RequestParam String format, @RequestParam(required = false) String style) {
        var type = switch (format) {
            case "txt" -> ExportFormat.TXT;
            case "md" -> ExportFormat.MD;
            case "bibtex" -> ExportFormat.BIBTEX;
            default -> throw new ClientInvalid("不支持的引文导出格式");
        };
        var content = tasks.export(account.username(), taskId, type, style == null ? null : CitationStyle.parse(style));
        var mediaType = switch (type) {
            case TXT -> "text/plain";
            case MD -> "text/markdown";
            case BIBTEX -> "application/x-bibtex";
        };
        var filename = "citations-" + taskId + (type == ExportFormat.BIBTEX ? ".bib" : "." + format);
        return ResponseEntity.ok().contentType(new MediaType(MediaType.parseMediaType(mediaType), StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
