package com.paperflow.client.citation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CitationTask(UUID taskId, String title, String originalText, CitationStyle style,
                           RetrievalPolicy retrievalPolicy, String engineProvider, Instant createdAt,
                           State state, CitationResult result, String annotatedText, String bibliography) {
    public record State(CitationTaskStatus status, CitationStage stage, double progress, String engineRunId,
                        String errorCode, String errorMessage, Instant updatedAt, Instant finishedAt) {}

    public record Detail(CitationTask task, List<CitationProgressEvent> events) {}

    public CitationTaskSummary summary() {
        var data = result == null ? CitationResult.EMPTY : result;
        int count = data.selectedReferenceIds().values().stream().mapToInt(List::size).sum();
        return new CitationTaskSummary(taskId, title, state.status(), state.stage(), state.progress(), style.id(),
                engineProvider, data.statements().size(), count, createdAt, state.updatedAt(),
                state.finishedAt(), state.errorMessage());
    }

    public CitationTask withState(State state) {
        return new CitationTask(taskId, title, originalText, style, retrievalPolicy, engineProvider, createdAt,
                state, result, annotatedText, bibliography);
    }

    public CitationTask withOutput(CitationStyle style, CitationResult result, String text, String bibliography) {
        return new CitationTask(taskId, title, originalText, style, retrievalPolicy, engineProvider, createdAt,
                state, result, text, bibliography);
    }
}
