package com.paperflow.adapters.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcCausalBatchQueryTest {
    @Test
    void derivesPendingReviewBatchStatusWhenOnlyReviewItemsRemain() {
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 0, 0, 2, 0, 0, 0, 0)).isEqualTo("PENDING_REVIEW");
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 0, 0, 1, 0, 1, 0, 0)).isEqualTo("PENDING_REVIEW");
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 0, 1, 1, 0, 0, 0, 0)).isEqualTo("RUNNING");
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 1, 0, 1, 0, 0, 0, 0)).isEqualTo("QUEUED");
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 0, 0, 0, 1, 0, 0, 0)).isEqualTo("RUNNING");
        assertThat(JdbcCausalBatchQuery.deriveStatus(false, 0, 0, 0, 0, 2, 0, 0)).isEqualTo("SUCCEEDED");
    }
}
