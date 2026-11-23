package com.paperflow.job;

import java.util.UUID;

public record JobItem(
        UUID jobId, JobKind kind, int sequence, String target, String paperId, Long fileRevision, Long recordVersion,
        String input, String state, String message, String result
) {
    JobItem withPaperId(String id) {
        return new JobItem(jobId, kind, sequence, target, id, fileRevision, recordVersion, input, state, message, result);
    }

    public boolean inputError() {
        return kind == JobKind.ORIGINAL_IMPORT && "FAILED".equals(state) && "{}".equals(input);
    }
}
