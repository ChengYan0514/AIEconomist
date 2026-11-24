package com.paperflow.client;

import com.paperflow.client.citation.BibtexExporter;
import com.paperflow.client.citation.CitationFormatter;
import com.paperflow.client.citation.model.CitationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BibtexExporterTest {
    private final BibtexExporter exporter = new BibtexExporter();

    @Test
    void deduplicatesReferencesAndEscapesFieldsWithUniqueKeys() {
        var sample = CitationFormatterTest.sample(Map.of("s1", List.of("first", "second"), "s2", List.of("first")));
        var first = CitationSamples.reference("first", "Trade {Costs} & Growth", 2023, "Jane Smith");
        var second = CitationSamples.reference("second", "Trade_2 #", 2023, "John Smith");
        var result = new CitationResult(sample.statements(), Map.of("s1", List.of(first, second), "s2", List.of(first)),
                sample.selectedReferenceIds(), Map.of(), List.of());
        String bibtex = exporter.bibtex(result);
        assertThat(bibtex).containsOnlyOnce("@article{smith2023,").contains("@article{smith2023-2,")
                .contains("title = {Trade \\{Costs\\} \\& Growth}", "title = {Trade\\_2 \\#}");
    }

    @Test
    void rendersPlainTextAndMarkdownSections() {
        var output = new CitationFormatter.Output("正文", "文献");
        assertThat(exporter.text(output)).isEqualTo("正文\n\nReferences\n文献");
        assertThat(exporter.markdown("研究", output)).isEqualTo("# 研究\n\n## 正文\n\n正文\n\n## 参考文献\n\n文献");
    }
}
