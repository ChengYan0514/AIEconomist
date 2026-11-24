package com.paperflow.causal.graph;

import com.paperflow.causal.graph.CausalFactReader.*;
import com.paperflow.causal.graph.CausalGraphModels.*;
import com.paperflow.causal.graph.CausalReadModelStore.*;
import com.paperflow.causal.model.SignCategory;

import java.time.Instant;
import java.util.*;

public final class CausalProjectionBuilder {
    public static final int OVERVIEW_MIN_RECORDS = 3;
    public static final int DEFAULT_GRAPH_MIN_RECORDS = 20;
    public static final int DEFAULT_GRAPH_MIN_DIVERSITY = 5;
    public static final int DEFAULT_GRAPH_MAX_NODES = 300;
    public static final int DEFAULT_GRAPH_MAX_EDGES = 500;
    private static final int METHOD_LIMIT = 10;
    private static final int FIELD_LIMIT = 200;

    private CausalProjectionBuilder() {}

    public static List<EdgeIndexRow> buildEdgeIndex(
            List<EdgeProjectionRow> rows, Map<String, List<WorkTopicRow>> topicsByWork, long revision
    ) {
        List<EdgeIndexRow> indexed = new ArrayList<>(rows.size());
        for (EdgeProjectionRow row : rows) {
            Set<String> subfields = new TreeSet<>();
            for (String workId : row.workIds()) {
                for (WorkTopicRow topic : topicsByWork.getOrDefault(workId, List.of())) {
                    if (topic.subfieldName() != null && !topic.subfieldName().isBlank()) {
                        subfields.add(topic.subfieldName());
                    }
                }
            }
            indexed.add(new EdgeIndexRow(row.claimId(), row.causeStandard(), row.effectStandard(),
                    row.recordCount(), row.paperCount(), row.diversity(),
                    row.positiveCount(), row.negativeCount(), row.nullCount(), row.mixedCount(),
                    List.copyOf(subfields), revision));
        }
        return indexed;
    }

    public static GraphSummary buildSummary(
            OverviewStats stats, List<String> subfields, List<String> methods, long revision, Instant now
    ) {
        var overview = new Overview(stats.totalClaimRecords(), stats.totalStandardClaims(), stats.totalPapers(),
                stats.totalNodes(), stats.totalStandardClaims(), stats.graphNodes(), stats.graphEdges(),
                OVERVIEW_MIN_RECORDS);
        var version = new DatasetVersion(revision, "revision-" + revision, now);
        return new GraphSummary(overview, subfields, methods, version);
    }

    public static GraphData buildGraph(List<EdgeIndexRow> edgeRows, int maxNodes) {
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, long[]> nodeCounts = new LinkedHashMap<>();
        for (EdgeIndexRow row : edgeRows) {
            int additions = (nodeCounts.containsKey(row.causeStandard()) ? 0 : 1)
                    + (!row.causeStandard().equals(row.effectStandard()) && !nodeCounts.containsKey(row.effectStandard()) ? 1 : 0);
            if (nodeCounts.size() + additions > maxNodes) continue;
            edges.add(toEdge(row.claimId(), row.causeStandard(), row.effectStandard(), row.recordCount(),
                    row.paperCount(), row.diversity(), row.positiveCount(), row.negativeCount(),
                    row.nullCount(), row.mixedCount()));
            nodeCounts.computeIfAbsent(row.causeStandard(), key -> new long[2])[0] += row.recordCount();
            nodeCounts.computeIfAbsent(row.effectStandard(), key -> new long[2])[1] += row.recordCount();
        }
        List<GraphNode> nodes = nodeCounts.entrySet().stream()
                .map(e -> new GraphNode(e.getKey(), e.getKey(), e.getValue()[0] + e.getValue()[1],
                        "未标注", e.getValue()[0], e.getValue()[1]))
                .toList();
        return new GraphData(nodes, edges);
    }

    public static FieldAnalysis buildFieldAnalysis(
            List<PaperClaimPair> pairs, Map<String, List<WorkTopicRow>> topicsByWork,
            List<NamedCountRow> methods, List<NamedCountRow> topVariables, List<GlobalRelationRow> topRelations
    ) {
        List<FieldItem> items = aggregateFields(pairs, topicsByWork);
        var insights = new FieldInsights(
                summarizeMethods(methods),
                topVariables.stream().map(r -> new NamedCount(r.name(), r.count())).toList(),
                topRelations.stream().map(r -> new GlobalRelation(
                        r.cause(), r.effect(), r.claimRecordCount(), r.paperCount(), r.methodCount())).toList());
        return new FieldAnalysis(items, insights);
    }

    private static List<FieldItem> aggregateFields(
            List<PaperClaimPair> pairs, Map<String, List<WorkTopicRow>> topicsByWork
    ) {
        Map<String, FieldBucket> buckets = new HashMap<>();
        for (PaperClaimPair pair : pairs) {
            Set<String> fieldKeys = new LinkedHashSet<>();
            List<WorkTopicRow> topics = topicsByWork.getOrDefault(pair.workId(), List.of());
            if (topics.isEmpty()) {
                fieldKeys.add("未标注\0未标注");
            } else {
                for (WorkTopicRow t : topics) {
                    String sf = blankDefault(t.subfieldName(), "未标注");
                    String tp = blankDefault(t.topicName(), "未标注");
                    fieldKeys.add(sf + "\0" + tp);
                }
            }
            for (String key : fieldKeys) {
                FieldBucket bucket = buckets.computeIfAbsent(key, FieldBucket::new);
                bucket.records += pair.recordCount();
                bucket.papers.add(pair.workId());
                if (pair.causeStandard() != null && !pair.causeStandard().isBlank()) bucket.variables.add(pair.causeStandard().strip());
                if (pair.effectStandard() != null && !pair.effectStandard().isBlank()) bucket.variables.add(pair.effectStandard().strip());
            }
        }
        return buckets.values().stream()
                .map(FieldBucket::toItem)
                .sorted(Comparator.comparingLong(FieldItem::claimRecordCount).reversed()
                        .thenComparing(FieldItem::subfield).thenComparing(FieldItem::topic))
                .limit(FIELD_LIMIT)
                .toList();
    }

    private static List<NamedCount> summarizeMethods(List<NamedCountRow> rows) {
        List<NamedCount> list = new ArrayList<>();
        long other = 0;
        for (int i = 0; i < rows.size(); i++) {
            NamedCountRow row = rows.get(i);
            if (i < METHOD_LIMIT) list.add(new NamedCount(blankDefault(row.name(), "未标注方法"), row.count()));
            else other += row.count();
        }
        if (other > 0) list.add(new NamedCount("其他", other));
        return list;
    }

    public static GraphEdge toEdge(
            long claimId, String cause, String effect, long records, long papers, long diversity,
            long positive, long negative, long nullCount, long mixed
    ) {
        Map<String, Long> breakdown = SignCategory.breakdown(positive, negative, nullCount, mixed);
        String dominant = SignCategory.dominant(breakdown);
        double disagreement = SignCategory.disagreement(records, breakdown, dominant);
        return new GraphEdge(claimId, cause, effect, records, papers, diversity, disagreement, dominant, breakdown);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static final class FieldBucket {
        final String subfield;
        final String topic;
        long records;
        final Set<String> papers = new HashSet<>();
        final Set<String> variables = new HashSet<>();

        FieldBucket(String key) {
            int sep = key.indexOf('\0');
            this.subfield = key.substring(0, sep);
            this.topic = key.substring(sep + 1);
        }

        FieldItem toItem() {
            return new FieldItem(subfield, topic, records, papers.size(), variables.size());
        }
    }
}
