package com.paperflow.causal.extraction;

import com.paperflow.causal.extraction.CausalExtractionModels.ClaimPayloadRow;

import java.util.List;
import java.util.UUID;

public interface CausalFactWriter {
    record PublishOutcome(boolean alreadyApplied, int rowCount) {}

    void checkNoLegacyDuplicates(String workId, List<ClaimPayloadRow> rows);
    PublishOutcome publishIdempotently(UUID runId, String workId, String payloadHash, List<ClaimPayloadRow> rows);
}
