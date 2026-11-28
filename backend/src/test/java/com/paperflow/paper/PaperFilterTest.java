package com.paperflow.paper;

import com.paperflow.paper.model.CausalStatus;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaperFilterTest {
    @Test
    void preservesCausalStatusAndNormalizesInputs() {
        var filter = new PaperFilter("  inflation ", " s123 ", 2020, 2024, PaperStatus.PUBLISHED,
                CausalStatus.EXTRACTED, false);
        assertThat(filter.q()).isEqualTo("inflation");
        assertThat(filter.sourceId()).isEqualTo("S123");
        assertThat(filter.status()).isEqualTo(PaperStatus.PUBLISHED);
        assertThat(filter.causalStatus()).isEqualTo(CausalStatus.EXTRACTED);
        assertThat(filter.archived()).isFalse();

        var withoutCausal = new PaperFilter(null, null, null, null, null, false);
        assertThat(withoutCausal.causalStatus()).isNull();
    }
}
