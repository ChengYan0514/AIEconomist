package com.paperflow.paper;

import com.paperflow.paper.model.PaperMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperMetadataTest {
    @Test
    void importUsesTheExistingNormalizedIdentityFormat() {
        var paper = new PaperMetadata(" s123 ", 2024, "Ｅxample   Title", " Author One ; Author Two ", null, null, null);

        assertThat(paper.paperId()).isEqualTo("0b25942be034bdccafd3bd2a7d70487650c584a9eb76a94f0f410daed7723141");
        assertThat(paper.sourceId()).isEqualTo("S123");
    }

    @Test
    void anOrderedAuthorListIsRequired() {
        assertThatThrownBy(() -> new PaperMetadata("S123", 2024, "Title", " ; ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
