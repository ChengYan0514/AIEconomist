package com.paperflow.job;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobSummary(
        UUID jobId, JobKind kind, String state, String username, UUID retryOf, int total, int ready, int queued,
        int running, int succeeded, int skipped, int failed, OffsetDateTime createdAt, OffsetDateTime finishedAt,
        OffsetDateTime confirmedAt, String scope, boolean hasRetryableFailure
) {
    boolean finishedWithFailures() {
        return failed > 0 && ready + queued + running == 0 && (kind != JobKind.ORIGINAL_IMPORT || confirmedAt != null);
    }

    public boolean canRetry() {
        return finishedWithFailures() && hasRetryableFailure;
    }
}
