package com.paperflow.causal.extraction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CausalExtractionModels {
    private CausalExtractionModels() {}

    public enum TargetMode { PAPER_IDS, WORK_IDS, SOURCE_IDS, FILTER }

    public record FilterSpec(String q, String sourceId, Integer yearFrom, Integer yearTo) {}

    public record TargetSpec(
            TargetMode mode, List<String> paperIds, List<String> workIds, List<String> sourceIds, FilterSpec filters
    ) {}

    public record EstimateRequest(TargetSpec target, String ingestionMode) {}

    public record EstimateResponse(
            boolean enabled, int targetTotal, int executableCount, Map<String, Long> skipReasons,
            String engineVersion, String ingestionMode, int maxCalls, int maxBatchTargets
    ) {}

    public record CreateBatchRequest(TargetSpec target, String ingestionMode) {}

    public record TargetCandidate(
            String paperId, String workId, String sourceId, String title, String skipReason
    ) {}

    public record BlockExportRow(String blockId, String blockType, String blockText, Integer titleLevel, int blockSeq) {}

    public record RunMetrics(
            int claimCount, int collisionCount, int callsUsed, int tokensUsed, List<String> modelVersions
    ) {
        public static RunMetrics empty() {
            return new RunMetrics(0, 0, 0, 0, List.of());
        }
    }

    public record CausalRunView(
            UUID runId, UUID batchId, String paperId, String workId, String sourceId, String paperTitle,
            String status, String stage, RunMetrics metrics, String errorCode, String errorMessage,
            boolean hasArtifacts, Instant updatedAt, Instant finishedAt
    ) {}

    public record CausalBatchView(
            UUID batchId, String ingestionMode, String status, boolean cancelled,
            Map<String, Object> scope, Map<String, Object> configSnapshot,
            int total, int queued, int running, int publishing, int succeeded, int zeroClaimPapers,
            int skipped, int failed, int cancelledCount, int totalClaims, int callsUsed,
            boolean canCancel, boolean canRetry, Instant createdAt, Instant finishedAt
    ) {}

    public record CausalBatchDetail(
            CausalBatchView batch, List<CausalRunView> runs, int page, int size, long totalRuns
    ) {}

    public record ArtifactInfo(String name, long sizeBytes, String sha256) {}

    public record EngineHealth(
            boolean healthy, String profileVersion, String chatModel, String embeddingModel,
            int defaultMaxCalls, String message
    ) {}

    public record EngineRunStatus(
            String engineRunId, String status, String stage, RunMetrics usage,
            String resultJson, String errorCode, String errorMessage
    ) {}

    public record ClaimPayloadRow(
            String paperId, String claimCause, String claimEffect, String claimHash,
            String claim, String cause, String effect, String signOfImpact, String typeOfRelationship,
            String evidence, String causalInferenceMethod, String evidenceMethodOtherDescription,
            boolean isMainContribution, String levelOfTentativeness,
            String sourcesOfExogenousVariation, String statisticalSignificance,
            BigDecimal causeScore, BigDecimal effectScore
    ) {}

    public record RunEvent(
            UUID batchId, UUID runId, String paperId, String workId,
            String status, String stage, RunMetrics metrics, String errorCode, String batchStatus, Instant at
    ) {}
}
