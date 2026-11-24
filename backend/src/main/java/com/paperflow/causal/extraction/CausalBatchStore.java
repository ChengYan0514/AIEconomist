package com.paperflow.causal.extraction;

import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.causal.model.ReviewPolicy;
import com.paperflow.causal.model.RunStage;
import com.paperflow.causal.model.RunStatus;

import java.util.List;
import java.util.UUID;

public interface CausalBatchStore {
    record BatchRow(UUID batchId, IngestionMode mode, ReviewPolicy reviewPolicy, String scopeJson, String configJson, boolean cancelled) {}

    record RunRow(
            UUID runId, UUID batchId, String paperId, String workId, String sourceId,
            String inputHash, String engineRunId, RunStatus status, RunStage stage, String metricsJson, String draftPayloadJson
    ) {}

    List<TargetCandidate> resolveTargets(TargetSpec target, int limitPlusOne);
    List<BlockExportRow> loadPublishedBlocks(String paperId);
    boolean isPaperEligible(String paperId, String expectedWorkId, String expectedSourceId);
    void createBatch(UUID batchId, IngestionMode mode, ReviewPolicy reviewPolicy, String scopeJson, String configJson, List<RunRow> runs);
    BatchRow findBatch(UUID batchId);
    CausalBatchView findBatchView(UUID batchId);
    List<CausalBatchView> listBatchViews(int offset, int size);
    long countBatches();
    List<CausalRunView> listRunViews(UUID batchId, String statusFilter, int offset, int size);
    long countRunViews(UUID batchId, String statusFilter);
    RunRow findRun(UUID runId);
    List<RunRow> listQueuedRuns(UUID batchId);
    List<RunRow> listActiveRuns(UUID batchId);
    List<RunRow> listInterruptedRuns();
    void markRunning(UUID runId, String inputHash, String engineRunId, RunStage stage);
    void updateProgress(UUID runId, RunStage stage, String metricsJson);
    void markPendingReview(UUID runId, String draftPayloadJson, String metricsJson);
    void markPublishing(UUID runId, String metricsJson);
    void markSucceeded(UUID runId, String metricsJson);
    void markFailed(UUID runId, String errorCode, String errorMessage, String metricsJson);
    void dismissReview(UUID runId);
    boolean cancelBatch(UUID batchId);
    int requeueRetryable(UUID batchId);
    void finalizeBatchIfComplete(UUID batchId);
    List<RunReviewDraft> listPendingReviewDrafts(UUID batchId);
    RunReviewDraft getRunReviewDraft(UUID runId);
}
