package com.paperflow.paper;

import com.paperflow.paper.model.CausalStatus;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CausalStatusTest {
    @Test
    void resolvesAllSevenStatusesWithExpectedPriority() {
        assertThat(CausalStatus.resolve("QUEUED", 5, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.EXTRACTING);
        assertThat(CausalStatus.resolve("RUNNING", 0, PaperStatus.UNPROCESSED, null)).isEqualTo(CausalStatus.EXTRACTING);
        assertThat(CausalStatus.resolve("PUBLISHING", 0, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.EXTRACTING);
        assertThat(CausalStatus.resolve("PENDING_REVIEW", 3, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.PENDING_REVIEW);
        assertThat(CausalStatus.resolve("FAILED", 2, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.EXTRACTED);
        assertThat(CausalStatus.resolve(null, 1, PaperStatus.UNPROCESSED, "W1")).isEqualTo(CausalStatus.EXTRACTED);
        assertThat(CausalStatus.resolve("FAILED", 0, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.FAILED);
        assertThat(CausalStatus.resolve("SUCCEEDED", 0, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.EXTRACTED_NO_CAUSAL);
        assertThat(CausalStatus.resolve(null, 0, PaperStatus.READY, "W1")).isEqualTo(CausalStatus.PREREQUISITE_MISSING);
        assertThat(CausalStatus.resolve(null, 0, PaperStatus.PUBLISHED, "  ")).isEqualTo(CausalStatus.PREREQUISITE_MISSING);
        assertThat(CausalStatus.resolve(null, 0, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.NOT_EXTRACTED);
        assertThat(CausalStatus.resolve("CANCELLED", 0, PaperStatus.PUBLISHED, "W1")).isEqualTo(CausalStatus.NOT_EXTRACTED);
    }

    @Test
    void enrichesPaperCausalClaimsWithoutOverridingActiveStatuses() {
        var base = new Paper("p1", false, "S1", "Journal", 2024, "Title", "Alice", null, null, null,
                "a.pdf", "v2/a.pdf", "PDF", 10L, 1, 1, false, "W1", null, null,
                PaperStatus.PUBLISHED, CausalStatus.NOT_EXTRACTED, 0, OffsetDateTime.now());
        var extracted = base.withCausalClaims(4);
        assertThat(extracted.causalStatus()).isEqualTo(CausalStatus.EXTRACTED);
        assertThat(extracted.causalClaimCount()).isEqualTo(4);

        var pending = new Paper("p2", false, "S1", "Journal", 2024, "Title", "Alice", null, null, null,
                "a.pdf", "v2/a.pdf", "PDF", 10L, 1, 1, false, "W2", null, null,
                PaperStatus.PUBLISHED, CausalStatus.PENDING_REVIEW, 0, OffsetDateTime.now());
        var pendingWithClaims = pending.withCausalClaims(2);
        assertThat(pendingWithClaims.causalStatus()).isEqualTo(CausalStatus.PENDING_REVIEW);
        assertThat(pendingWithClaims.causalClaimCount()).isEqualTo(2);
        assertThat(base.withCausalClaims(0)).isSameAs(base);
    }
}
