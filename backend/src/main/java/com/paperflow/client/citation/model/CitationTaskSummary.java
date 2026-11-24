package com.paperflow.client.citation.model;

import java.time.Instant;
import java.util.UUID;

public record CitationTaskSummary(UUID taskId, String title, CitationTaskStatus status, CitationStage stage,
                                  double progress, String citationStyle, String engineProvider, int statementCount,
                                  int selectedReferenceCount, Instant createdAt, Instant updatedAt, Instant finishedAt,
                                  String errorMessage) {}
