package com.paperflow.client.adapters.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStatement;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskSummary;
import com.paperflow.client.citation.model.EvidenceStats;
import com.paperflow.client.citation.model.RetrievalPolicy;

import java.util.List;
import java.util.Map;

public record CitationTaskDetailResponse(@JsonUnwrapped CitationTaskSummary summary, String originalText,
                                         RetrievalPolicy retrievalPolicy, List<CitationStatement> statements,
                                         Map<String, List<CitationReference>> referencesByStatement,
                                         Map<String, List<String>> selectedReferenceIds,
                                         Map<String, EvidenceStats> evidenceStats, String annotatedText,
                                         String bibliography, List<String> warnings, String errorCode,
                                         List<CitationProgressEvent> events) {
    static CitationTaskDetailResponse from(CitationTask.Detail detail) {
        var task = detail.task();
        var result = task.result() == null ? CitationResult.EMPTY : task.result();
        return new CitationTaskDetailResponse(task.summary(), task.originalText(), task.retrievalPolicy(),
                result.statements(), result.referencesByStatement(), result.selectedReferenceIds(), result.evidenceStats(),
                task.annotatedText(), task.bibliography(), result.warnings(), task.state().errorCode(), detail.events());
    }
}
