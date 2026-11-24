package com.paperflow.client.citation.model;

import java.util.List;
import java.util.Map;

public record CitationResult(List<CitationStatement> statements, Map<String, List<CitationReference>> referencesByStatement,
                             Map<String, List<String>> selectedReferenceIds, Map<String, EvidenceStats> evidenceStats,
                             List<String> warnings) {
    public static final CitationResult EMPTY = new CitationResult(null, null, null, null, null);

    public CitationResult {
        statements = statements == null ? List.of() : List.copyOf(statements);
        referencesByStatement = referencesByStatement == null ? Map.of() : Map.copyOf(referencesByStatement);
        selectedReferenceIds = selectedReferenceIds == null ? Map.of() : Map.copyOf(selectedReferenceIds);
        evidenceStats = evidenceStats == null ? Map.of() : Map.copyOf(evidenceStats);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public CitationResult withReferences(Map<String, List<CitationReference>> references) {
        return new CitationResult(statements, references, selectedReferenceIds, evidenceStats, warnings);
    }

    public CitationResult withSelection(Map<String, List<String>> selection) {
        return new CitationResult(statements, referencesByStatement, selection, evidenceStats, warnings);
    }

    public List<CitationReference> selected(CitationStatement statement) {
        if (!statement.needsCitation()) return List.of();
        var references = referencesByStatement.getOrDefault(statement.statementId(), List.of());
        return selectedReferenceIds.getOrDefault(statement.statementId(), List.of()).stream()
                .distinct()
                .flatMap(id -> references.stream().filter(reference -> id.equals(reference.referenceId())).limit(1))
                .toList();
    }
}
