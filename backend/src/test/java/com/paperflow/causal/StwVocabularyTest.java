package com.paperflow.causal;

import com.paperflow.adapters.causal.StwCsvLoader;
import com.paperflow.audit.AuditLog;
import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.causal.graph.CausalFactReader.NamedCountRow;
import com.paperflow.causal.graph.CausalGraphService;
import com.paperflow.causal.graph.CausalReadModelRefresher;
import com.paperflow.causal.graph.CausalReadModelStore;
import com.paperflow.causal.graph.StwVocabulary;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StwVocabularyTest {
    @Test
    void loadsBundledProjectStwLabelsCsvAndRanksPrefixBeforeSubstring() {
        StwVocabulary vocab = StwCsvLoader.loadBundled();
        assertThat(vocab.size()).isGreaterThan(6000);

        List<String> exactPrefix = vocab.search("fiscal pol", 10);
        assertThat(exactPrefix).containsExactly(
                "Fiscal policy",
                "Countercyclical fiscal policy",
                "European fiscal policy",
                "Local fiscal policy"
        );

        List<String> fisHits = vocab.search("fis", 10);
        assertThat(fisHits).hasSize(10);
        assertThat(fisHits.get(0)).isEqualTo("Fiscal administration");
        assertThat(fisHits).contains("Fiscal policy");
        assertThat(fisHits).allMatch(term -> term.toLowerCase().startsWith("fis"));

        List<String> policyHits = vocab.search("policy", 5);
        assertThat(policyHits).hasSize(5);
        assertThat(policyHits.get(0).toLowerCase()).startsWith("policy");

        assertThat(vocab.search("nonexistent-stw-term-xyz", 10)).isEmpty();
    }

    @Test
    void parsesCustomCsvWithQuotedCommasDeduplicationAndHeaderValidation() throws Exception {
        String csv = """
                id,label_en,level
                1,"Trade, international",
                2,Fiscal policy,
                3,Fiscal policy,
                4,EU fiscal relations,
                5,   ,
                """;
        StwVocabulary vocab = StwCsvLoader.load(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(vocab.size()).isEqualTo(3);
        assertThat(vocab.search("fis", 10)).containsExactly("Fiscal policy", "EU fiscal relations");
        assertThat(vocab.search("international", 10)).containsExactly("Trade, international");

        String invalidCsv = "id,name\n1,Fiscal policy\n";
        assertThatThrownBy(() -> StwCsvLoader.load(new ByteArrayInputStream(invalidCsv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("label_en");
    }

    @Test
    void graphServiceSearchesStwCsvFirstAndFallsBackToGraphVariablesWhenUnmatched() {
        var facts = mock(CausalFactReader.class);
        var store = mock(CausalReadModelStore.class);
        var refresher = mock(CausalReadModelRefresher.class);
        var audit = mock(AuditLog.class);
        var vocab = new StwVocabulary(List.of("Fiscal policy", "Monetary policy"));
        var service = new CausalGraphService(facts, store, refresher, vocab, audit);

        assertThat(service.searchTerms("fis", 5)).containsExactly("Fiscal policy");
        verifyNoInteractions(store);

        when(store.searchVariables("custom var", 5)).thenReturn(List.of(new NamedCountRow("Custom Variable", 3)));
        assertThat(service.searchTerms("custom var", 5)).containsExactly("Custom Variable");
        verify(refresher).summary();
        verify(store).searchVariables("custom var", 5);
    }
}
