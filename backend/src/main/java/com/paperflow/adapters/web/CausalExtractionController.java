package com.paperflow.adapters.web;

import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.extraction.CausalExtractionService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/causal")
public class CausalExtractionController {
    private final CausalExtractionService service;

    public CausalExtractionController(CausalExtractionService service) {
        this.service = service;
    }

    @PostMapping("/extractions/estimate")
    EstimateResponse estimate(@RequestBody EstimateRequest request) {
        return service.estimate(request);
    }

    @PostMapping("/extractions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CausalBatchView create(@RequestBody CreateBatchRequest request, Principal actor) {
        return service.create(actor.getName(), request);
    }

    @GetMapping("/extractions")
    Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(page, size);
    }

    @GetMapping("/extractions/{batchId}")
    CausalBatchDetail detail(
            @PathVariable UUID batchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.detail(batchId, status, page, size);
    }

    @GetMapping(path = "/extractions/{batchId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable UUID batchId) {
        return service.subscribeEvents(batchId);
    }

    @PostMapping("/extractions/{batchId}/cancel")
    CausalBatchView cancel(@PathVariable UUID batchId, Principal actor) {
        return service.cancel(actor.getName(), batchId);
    }

    @PostMapping("/extractions/{batchId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CausalBatchView retry(@PathVariable UUID batchId, Principal actor) {
        return service.retry(actor.getName(), batchId);
    }

    @GetMapping("/extractions/{batchId}/review")
    List<RunReviewDraft> getBatchReview(@PathVariable UUID batchId) {
        return service.getBatchReviewDrafts(batchId);
    }

    @PostMapping("/extractions/{batchId}/review")
    Map<String, Object> submitBatchReview(
            @PathVariable UUID batchId,
            @RequestBody SubmitBatchReviewRequest request,
            Principal actor
    ) {
        service.submitBatchReview(actor.getName(), batchId, request == null ? List.of() : request.runs());
        return Map.of("batchId", batchId, "status", "SUBMITTED");
    }

    @GetMapping("/runs/{runId}/review")
    RunReviewDraft getRunReview(@PathVariable UUID runId) {
        return service.getRunReviewDraft(runId);
    }

    @PostMapping("/runs/{runId}/review")
    Map<String, Object> submitRunReview(
            @PathVariable UUID runId,
            @RequestBody SubmitRunReviewRequest request,
            Principal actor
    ) {
        service.submitRunReview(actor.getName(), runId, request == null ? List.of() : request.claims());
        return Map.of("runId", runId, "status", "PUBLISHED");
    }

    @DeleteMapping("/runs/{runId}/review")
    Map<String, Object> dismissRunReview(@PathVariable UUID runId, Principal actor) {
        service.dismissRunReview(actor.getName(), runId);
        return Map.of("runId", runId, "status", "CANCELLED");
    }

    @GetMapping("/runs/{runId}/artifacts")
    List<ArtifactInfo> artifacts(@PathVariable UUID runId) throws IOException, InterruptedException {
        return service.artifacts(runId);
    }

    @GetMapping("/runs/{runId}/artifacts/{name}")
    ResponseEntity<InputStreamResource> download(
            @PathVariable UUID runId, @PathVariable String name
    ) throws IOException, InterruptedException {
        var stream = service.openArtifact(runId, name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }
}
