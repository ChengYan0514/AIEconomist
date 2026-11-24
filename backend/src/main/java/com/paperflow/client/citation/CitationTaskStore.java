package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.citation.model.CitationTaskSummary;
import com.paperflow.client.citation.model.CitationUpdate;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface CitationTaskStore {
    CitationTask.Detail create(String username, CitationTask task, CitationUpdate event, int activeLimit);

    Page list(String username, CitationTaskStatus status, int page, int size);

    <T> T locked(UUID id, String username, Function<CitationTask, T> action);

    List<UUID> activeTaskIds();

    List<CitationProgressEvent> events(UUID id);

    CitationProgressEvent append(UUID id, CitationUpdate event);

    void save(CitationTask task);

    void delete(UUID id);

    record Page(List<CitationTaskSummary> items, int page, int size, long total) {}
}
