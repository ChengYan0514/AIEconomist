package com.paperflow.job;

import java.util.List;
import java.util.UUID;

public interface JobStore {
    int create(UUID id, JobKind kind, String username, String scope, UUID retryOf);
    int addItems(List<JobItem> items);
    JobSummary get(UUID id);
    List<JobSummary> list(JobKind kind, String state, int size, long offset);
    long count(JobKind kind, String state);
    List<JobItem> items(UUID id, String state, int size, long offset);
    long countItems(UUID id, String state);
    int confirm(UUID id);
    int queueReady(UUID id);
    int retryFailed(UUID id, UUID previous);
    JobItem claim(long seconds);
    int expireClaims();
    boolean claimActive(UUID id, int sequence);
    int checkpoint(UUID id, int sequence, String result);
    int finish(UUID id, int sequence, String state, String message, String result);
}
