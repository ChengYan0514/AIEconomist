package com.paperflow.job;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaperJobRecord(UUID jobId, int sequence, JobKind kind, String state, String message,
                             OffsetDateTime createdAt) {}
