package com.paperflow.causal.graph;

import com.paperflow.causal.graph.CausalFactReader.*;
import com.paperflow.causal.graph.CausalGraphModels.*;
import com.paperflow.causal.graph.CausalReadModelStore.*;
import com.paperflow.causal.model.SignCategory;

import java.util.*;

final class CausalEnricher {
    private CausalEnricher() {}

    static EdgeDetail edgeDetail(
            EdgeAggregate edge, List<RawClaimRow> rawClaims,
            Map<String, WorkMeta> meta, Map<String, List<WorkTopicRow>> topics
    ) {
        List<ClaimItem> claims = enrichClaims(rawClaims, meta, topics);
        Set<String> subfields = new HashSet<>();
        Set<String> topicNames = new HashSet<>();
        IntSummaryStatistics years = new IntSummaryStatistics();
        for (String w : edge.workIds()) {
            WorkMeta m = meta.get(w);
            if (m != null && m.publicationYear() != null) years.accept(m.publicationYear());
            for (WorkTopicRow t : topics.getOrDefault(w, List.of())) {
                if (t.subfieldName() != null && !t.subfieldName().isBlank()) subfields.add(t.subfieldName());
                if (t.topicName() != null && !t.topicName().isBlank()) topicNames.add(t.topicName());
            }
        }
        int span = years.getCount() == 0 ? 0 : years.getMax() - years.getMin();
        return new EdgeDetail(toEdge(edge), new EdgeStats(subfields.size(), topicNames.size(), span, edge.methods()), claims);
    }

    static List<ClaimItem> enrichClaims(
            List<RawClaimRow> rawClaims, Map<String, WorkMeta> metaMap, Map<String, List<WorkTopicRow>> topicMap
    ) {
        return rawClaims.stream().map(c -> {
            WorkMeta m = metaMap.get(c.workId());
            WorkTopicRow t = topicMap.getOrDefault(c.workId(), List.of()).stream().findFirst().orElse(null);
            return new ClaimItem(c.recordId(), c.workId(), m == null ? null : m.paperId(),
                    m == null ? null : m.title(), m == null ? null : m.publicationYear(),
                    m == null ? null : m.sourceId(), m == null ? null : m.sourceName(),
                    t == null ? null : t.topicName(), t == null ? null : t.subfieldName(),
                    c.claimId(), c.claim(), c.cause(), c.effect(), c.causeStandard(), c.effectStandard(),
                    c.signOfImpact(), SignCategory.classify(c.signOfImpact()), c.typeOfRelationship(),
                    c.causalInferenceMethod(), c.evidenceMethodOtherDescription(), c.isMainContribution(),
                    c.levelOfTentativeness(), c.sourcesOfExogenousVariation(), c.statisticalSignificance(),
                    c.causeScore(), c.effectScore(), c.evidence());
        }).toList();
    }

    static GraphData buildPaperGraph(List<ClaimItem> claims) {
        Map<String, long[]> nodes = new LinkedHashMap<>();
        Map<Long, List<ClaimItem>> byClaim = new LinkedHashMap<>();
        for (ClaimItem c : claims) {
            nodes.computeIfAbsent(c.causeStandard(), k -> new long[2])[0]++;
            nodes.computeIfAbsent(c.effectStandard(), k -> new long[2])[1]++;
            byClaim.computeIfAbsent(c.claimId(), k -> new ArrayList<>()).add(c);
        }
        List<GraphEdge> edges = byClaim.values().stream().map(CausalEnricher::paperEdge).toList();
        List<GraphNode> graphNodes = nodes.entrySet().stream()
                .map(e -> new GraphNode(e.getKey(), e.getKey(), e.getValue()[0] + e.getValue()[1],
                        "未标注", e.getValue()[0], e.getValue()[1])).toList();
        return new GraphData(graphNodes, edges);
    }

    private static GraphEdge paperEdge(List<ClaimItem> list) {
        ClaimItem first = list.get(0);
        long pos = list.stream().filter(i -> SignCategory.POSITIVE.equals(i.signCategory())).count();
        long neg = list.stream().filter(i -> SignCategory.NEGATIVE.equals(i.signCategory())).count();
        long nul = list.stream().filter(i -> SignCategory.NULL.equals(i.signCategory())).count();
        long mix = list.size() - pos - neg - nul;
        long div = list.stream().map(ClaimItem::causalInferenceMethod).filter(v -> v != null && !v.isBlank()).distinct().count();
        return CausalProjectionBuilder.toEdge(first.claimId(), first.causeStandard(), first.effectStandard(),
                list.size(), 1, div, pos, neg, nul, mix);
    }

    static GraphEdge toEdge(EdgeAggregate row) {
        return CausalProjectionBuilder.toEdge(row.claimId(), row.causeStandard(), row.effectStandard(),
                row.recordCount(), row.paperCount(), row.diversity(),
                row.positiveCount(), row.negativeCount(), row.nullCount(), row.mixedCount());
    }

    static Map<String, Long> countSubfields(List<String> workIds, Map<String, List<WorkTopicRow>> topics) {
        Map<String, Long> raw = new HashMap<>();
        for (String w : workIds) {
            var list = topics.getOrDefault(w, List.of());
            if (list.isEmpty()) raw.merge("未标注", 1L, Long::sum);
            else list.stream().map(t -> t.subfieldName() == null || t.subfieldName().isBlank() ? "未标注" : t.subfieldName())
                    .distinct().forEach(sf -> raw.merge(sf, 1L, Long::sum));
        }
        Map<String, Long> sorted = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    static Map<String, Long> countYears(List<String> workIds, Map<String, WorkMeta> meta) {
        Map<String, Long> counts = new TreeMap<>();
        for (String w : workIds) {
            WorkMeta m = meta.get(w);
            String year = m == null || m.publicationYear() == null ? "未知" : String.valueOf(m.publicationYear());
            counts.merge(year, 1L, Long::sum);
        }
        return counts;
    }
}
