package com.paperflow.job;

import java.util.UUID;

public record JobItem(
        UUID jobId, JobKind kind, int sequence, String target, String paperId, Long fileRevision, Long recordVersion,
        String input, String state, String message, String result
) {}
