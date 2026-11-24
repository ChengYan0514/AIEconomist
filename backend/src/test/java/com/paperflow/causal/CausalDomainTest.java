package com.paperflow.causal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalExtractionModels.BlockExportRow;
import com.paperflow.causal.extraction.CausalPayloadValidator;
import com.paperflow.causal.graph.CausalFactReader.*;
import com.paperflow.causal.graph.CausalProjectionBuilder;
import com.paperflow.causal.graph.CausalReadModelStore.EdgeIndexRow;
import com.paperflow.causal.graph.CausalReadModelStore.WorkTopicRow;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.causal.model.SignCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CausalDomainTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void classifiesAllLegacyNullAliasesAndComputesDominantAndDisagreement() {
        assertThat(SignCategory.classify("positive")).isEqualTo("positive");
        assertThat(SignCategory.classify("NEGATIVE")).isEqualTo("negative");
        for (String alias : List.of("null_efffect", "null_effect", "null effect", "null", " NULL_EFFECT ")) {
            assertThat(SignCategory.classify(alias)).isEqualTo("null");
        }
        assertThat(SignCategory.classify("u_shaped")).isEqualTo("mixed");
        assertThat(SignCategory.classify(null)).isEqualTo("mixed");

        var breakdown = SignCategory.breakdown(6, 4, 0, 0);
        assertThat(SignCategory.dominant(breakdown)).isEqualTo("positive");
        assertThat(SignCategory.disagreement(10, breakdown, "positive")).isEqualTo(0.4);
    }

    @Test
    void buildsEdgeIndexGraphAndFieldAnalysisWithMethodRollup() {
        var edgeRows = List.of(
                new EdgeProjectionRow(1L, "Monetary Policy", "Inflation", 5, 3, 2, 3, 1, 1, 0, List.of("W1", "W2")),
                new EdgeProjectionRow(2L, "Minimum Wage", "Employment", 4, 2, 1, 1, 3, 0, 0, List.of("W2", "W3"))
        );
        var topics = Map.of(
                "W1", List.of(new WorkTopicRow("W1", "Macroeconomics", "Inflation Dynamics")),
                "W2", List.of(new WorkTopicRow("W2", "Labor Economics", "Wage Policy"))
        );
        var indexed = CausalProjectionBuilder.buildEdgeIndex(edgeRows, topics, 7L);
        assertThat(indexed.get(0).subfields()).containsExactly("Labor Economics", "Macroeconomics");
        assertThat(indexed.get(1).subfields()).containsExactly("Labor Economics");

        var graph = CausalProjectionBuilder.buildGraph(indexed, 3);
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);

        var methods = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new NamedCountRow("Method-" + i, 20L - i)).toList();
        var pairs = List.of(
                new PaperClaimPair("W1", 1L, "Monetary Policy", "Inflation", 3),
                new PaperClaimPair("W3", 2L, "Minimum Wage", "Employment", 4)
        );
        var fields = CausalProjectionBuilder.buildFieldAnalysis(pairs, topics, methods,
                List.of(new NamedCountRow("Inflation", 5)),
                List.of(new GlobalRelationRow("Monetary Policy", "Inflation", 5, 3, 2)));
        assertThat(fields.items()).extracting(i -> i.subfield() + ":" + i.topic())
                .containsExactly("未标注:未标注", "Macroeconomics:Inflation Dynamics");
        assertThat(fields.insights().methodCounts()).hasSize(11);
        assertThat(fields.insights().methodCounts().get(10).name()).isEqualTo("其他");
        assertThat(fields.insights().methodCounts().get(10).count()).isEqualTo(17L);

        var summary = CausalProjectionBuilder.buildSummary(
                new OverviewStats(9, 2, 3, 4, 4, 2), List.of("Macroeconomics"), List.of("DID"), 7L, Instant.EPOCH);
        assertThat(summary.version().label()).isEqualTo("revision-7");
    }

    @Test
    void ranksSubfieldsByEdgeFrequencyAndExcludesUnused() {
        var edges = List.of(
                new EdgeIndexRow(1L, "A", "B", 10, 4, 2, 8, 2, 0, 0, List.of("Public Economics", "Finance"), 1L),
                new EdgeIndexRow(2L, "B", "C", 5, 2, 1, 5, 0, 0, 0, List.of("Finance", "Labor Economics"), 1L),
                new EdgeIndexRow(3L, "C", "D", 3, 1, 1, 3, 0, 0, 0, List.of("Finance", "Development Economics"), 1L)
        );
        assertThat(CausalProjectionBuilder.rankSubfields(edges))
                .containsExactly("Finance", "Development Economics", "Labor Economics", "Public Economics");
    }

    @Test
    void validatesCausalPayloadHashBoundsAndScope() {
        var validator = new CausalPayloadValidator(json);
        String hash = CausalPayloadValidator.claimHash("Minimum Wage", "Employment");
        String validJson = """
                {
                  "workId": "W123", "claimCount": 1, "collisionCount": 0, "calls": 12, "tokens": 500,
                  "modelVersions": ["qwen-max"],
                  "rows": [{
                    "paper_id": "W123", "claim_cause": "Minimum Wage", "claim_effect": "Employment",
                    "claim_hash": "%s", "claim": "Higher wage lowers employment",
                    "cause": "minimum wage", "effect": "employment", "sign_of_impact": "negative",
                    "type_of_relationship": "causal", "evidence": "Table 2 col 1",
                    "causal_inference_method": "DID", "is_main_contribution": true,
                    "cause_score": 0.912, "effect_score": 0.875
                  }]
                }
                """.formatted(hash);
        var validated = validator.parseAndValidate("W123", validJson);
        assertThat(validated.rows()).hasSize(1);
        assertThat(validated.metrics().callsUsed()).isEqualTo(12);
        assertThat(IngestionMode.parse("PR_COMPAT", IngestionMode.STANDARDIZED)).isEqualTo(IngestionMode.RAW);
        assertThat(IngestionMode.STANDARDIZED.engineMode()).isEqualTo("STANDARDIZED");

        String h1 = validator.inputHash("p1", "W123", "S1", List.of(new BlockExportRow("b1", "text", "hello", null, 0)));
        String h2 = validator.inputHash("p1", "W123", "S1", List.of(new BlockExportRow("b1", "text", "changed", null, 0)));
        assertThat(h1).isNotEqualTo(h2);

        assertThatThrownBy(() -> validator.parseAndValidate("W999", validJson))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INVALID_RESULT");
    }
}
