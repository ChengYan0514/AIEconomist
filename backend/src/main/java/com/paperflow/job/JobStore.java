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
    List<PaperJobRecord> paperHistory(String paperId, int size, long offset);
    long countPaperHistory(String paperId);
    int confirm(UUID id);
    int queueReady(UUID id);
    int retryFailed(UUID id, UUID previous);
    JobItem claim(long seconds);
    List<JobItem> expireClaims();
    String username(UUID id);
    boolean claimActive(UUID id, int sequence);
    int checkpoint(UUID id, int sequence, String result);
    int finish(UUID id, int sequence, String paperId, String state, String message, String result);
}
