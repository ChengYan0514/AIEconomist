package com.paperflow.client;

import com.paperflow.client.citation.CitationFormatter;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStatement;
import com.paperflow.client.citation.model.CitationStyle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationFormatterTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            apa | Output rose (Smith, 2023; Jones & Brown, 2020; Young et al., 2019)。 Demand fell (Jones & Brown, 2020).
            ieee | Output rose [1–3]。 Demand fell [2].
            chicago-author-date | Output rose (Smith 2023; Jones and Brown 2020; Young et al. 2019)。 Demand fell (Jones and Brown 2020).
            mla | Output rose (Smith; Jones and Brown; Young et al.)。 Demand fell (Jones and Brown).
            """)
    void insertsCitationsBeforePunctuationAndRecoversInvalidOffsets(String style, String expected) {
        var result = sample(Map.of("s1", List.of("one", "two", "three"), "s2", List.of("two")));
        var rendered = new CitationFormatter().format("Output rose。 Demand fell.", CitationStyle.parse(style), result);
        assertThat(rendered.annotatedText()).isEqualTo(expected);
        assertThat(rendered.bibliography()).contains("Single author", "Two authors", "Three authors");
    }

    @org.junit.jupiter.api.Test
    void emptySelectionRemovesTheCitationAndBibliography() {
        var rendered = new CitationFormatter().format("Output rose。 Demand fell.", CitationStyle.IEEE,
                sample(Map.of("s1", List.of())));
        assertThat(rendered.annotatedText()).isEqualTo("Output rose。 Demand fell.");
        assertThat(rendered.bibliography()).isEmpty();
    }

    static CitationResult sample(Map<String, List<String>> selection) {
        var one = CitationSamples.reference("one", "Single author", 2023, "Jane Smith");
        var two = CitationSamples.reference("two", "Two authors", 2020, "Ava Jones", "Ben Brown");
        var three = CitationSamples.reference("three", "Three authors", 2019, "Carol Young", "Sam Lee", "May Lu");
        return new CitationResult(List.of(
                new CitationStatement("s1", "Output rose。", 0, 12, true, null, null),
                new CitationStatement("s2", "Demand fell.", 80, 90, true, null, null)),
                Map.of("s1", List.of(one, two, three), "s2", List.of(two)), selection, Map.of(), List.of());
    }
}
