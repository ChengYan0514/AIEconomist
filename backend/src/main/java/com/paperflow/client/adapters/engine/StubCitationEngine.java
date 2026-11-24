package com.paperflow.client.adapters.engine;

import com.paperflow.client.citation.CitationEngine;
import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationStatement;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.citation.model.EvidenceChunk;
import com.paperflow.client.citation.model.EvidenceStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import static com.paperflow.client.citation.model.CitationStage.*;
import static com.paperflow.client.citation.model.CitationUpdate.EventType.STAGE_STARTED;

public final class StubCitationEngine implements CitationEngine {
    private static final Pattern SENTENCE = Pattern.compile("[^.!?。！？\\r\\n]+[.!?。！？]*|[.!?。！？]+");
    private static final List<CitationUpdate> STAGES = List.of(
            stage(PARSE_TEXT, 0.20, "识别引文陈述"), stage(PLAN_RETRIEVAL, 0.30, "规划文献检索"),
            stage(RETRIEVE_REFERENCES, 0.55, "检索示例参考文献"), stage(RANK_EVIDENCE, 0.75, "整理支持证据"));
    private static final List<Example> EXAMPLES = List.of(
            new Example("W123", null, "Economics Research Demonstration", 2024, "Example Journal", List.of("Jane Example")),
            new Example(null, "10.1086/261420", "Endogenous Technological Change", 1990,
                    "Journal of Political Economy", List.of("Paul Romer")),
            new Example(null, "10.2307/1926047", "A Contribution to the Theory of Economic Growth", 1956,
                    "The Quarterly Journal of Economics", List.of("Robert Solow")),
            new Example(null, "10.1257/aer.91.5.1369", "The Colonial Origins of Comparative Development", 2001,
                    "American Economic Review", List.of("Daron Acemoglu", "Simon Johnson", "James Robinson")),
            new Example(null, "10.2307/2118477", "A Model of Growth Through Creative Destruction", 1992,
                    "Econometrica", List.of("Philippe Aghion", "Peter Howitt")),
            new Example(null, "10.2307/1884513", "The Market for Lemons", 1970,
                    "The Quarterly Journal of Economics", List.of("George Akerlof")));

    @Override
    public String providerId() {
        return "stub";
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public CitationResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled)
            throws InterruptedException {
        for (var change : STAGES) {
            cancelled.check();
            sink.update(change);
            Thread.sleep(250);
        }
        cancelled.check();
        return result(request.text());
    }

    private CitationResult result(String text) {
        var statements = statements(text);
        var candidates = new LinkedHashMap<String, List<CitationReference>>();
        var selections = new LinkedHashMap<String, List<String>>();
        var stats = new LinkedHashMap<String, EvidenceStats>();
        for (var statement : statements) {
            var refs = new ArrayList<CitationReference>();
            for (int i = 0; i < EXAMPLES.size(); i++) refs.add(EXAMPLES.get(i).reference(statement, i));
            candidates.put(statement.statementId(), refs);
            selections.put(statement.statementId(), List.of(refs.get(0).referenceId(), refs.get(1).referenceId()));
            stats.put(statement.statementId(), new EvidenceStats(refs.size(), refs.size(), refs.size(),
                    new EvidenceStats.Rejected(0, 0, 0, 0, 0), false, false));
        }
        return new CitationResult(statements, candidates, selections, stats, List.of("当前引文由内置演示数据生成"));
    }

    private List<CitationStatement> statements(String text) {
        var result = new ArrayList<CitationStatement>();
        var matcher = SENTENCE.matcher(text);
        while (result.size() < 3 && matcher.find()) {
            String sentence = matcher.group().strip();
            if (sentence.isEmpty()) continue;
            int start = matcher.start() + matcher.group().indexOf(sentence);
            result.add(statement(result.size() + 1, sentence, start));
        }
        if (result.isEmpty()) result.add(statement(1, text, 0));
        return result;
    }

    private CitationStatement statement(int index, String sentence, int start) {
        return new CitationStatement("s" + index, sentence, start, start + sentence.length(), true, sentence, 0.9);
    }

    private static CitationUpdate stage(CitationStage stage, double progress, String message) {
        return new CitationUpdate(stage, STAGE_STARTED, progress, message);
    }

    private record Example(String workId, String doi, String title, int year, String venue, List<String> authors) {
        CitationReference reference(CitationStatement statement, int index) {
            String id = "demo-" + (index + 1);
            double score = (96 - 6 * index) / 100.0;
            var chunk = new EvidenceChunk(statement.statementId() + "-" + id, "演示证据",
                    "示例候选文献《" + title + "》的证据片段。", score, score, 0.9, "用于演示人工审阅与文献切换");
            return new CitationReference(id, workId, null, doi, title, authors, year, venue, null, null,
                    CitationReference.MetadataSource.ENGINE, score, score, 0.9, List.of(chunk));
        }
    }
}
