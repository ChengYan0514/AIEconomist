package com.paperflow.causal.graph;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.graph.CausalFactReader.*;
import com.paperflow.causal.graph.CausalGraphModels.*;
import com.paperflow.causal.graph.CausalReadModelStore.*;
import com.paperflow.causal.model.CausalNotFound;

import java.util.*;

public final class CausalGraphService {
    private static final int DETAIL_CLAIM_LIMIT = 500;
    private static final int NODE_EDGE_LIMIT = 100;
    private final CausalFactReader facts;
    private final CausalReadModelStore store;
    private final CausalReadModelRefresher refresher;
    private final StwVocabulary stwVocabulary;
    private final AuditLog audit;

    public CausalGraphService(
            CausalFactReader facts, CausalReadModelStore store, CausalReadModelRefresher refresher,
            StwVocabulary stwVocabulary, AuditLog audit
    ) {
        this.facts = facts;
        this.store = store;
        this.refresher = refresher;
        this.stwVocabulary = stwVocabulary;
        this.audit = audit;
    }

    public GraphSummary summary() {
        return refresher.summary();
    }

    public FieldAnalysis fields() {
        return refresher.fields();
    }

    public RefreshResult refreshReadModel(String actor) {
        RefreshResult result = refresher.refreshNow();
        audit.record(actor, "CAUSAL_READ_MODEL_REFRESH", "CAUSAL_GRAPH", "read-model", AuditOutcome.SUCCEEDED,
                Map.of("revision", result.revision(), "indexedEdges", result.indexedEdges()));
        return result;
    }

    public GraphData graph(
            Integer minRecordCount, Integer minPaperCount, Integer minDiversity,
            List<String> subfields, String query, Integer maxNodes, Integer maxEdges
    ) {
        int records = clamp(minRecordCount, CausalProjectionBuilder.OVERVIEW_MIN_RECORDS, 1, 1_000_000);
        Integer papers = minPaperCount == null || minPaperCount < 1 ? null : minPaperCount;
        int diversity = clamp(minDiversity, 1, 1, 1_000);
        int nodesLimit = clamp(maxNodes, CausalProjectionBuilder.DEFAULT_GRAPH_MAX_NODES, 1, 1_000);
        int edgesLimit = clamp(maxEdges, CausalProjectionBuilder.DEFAULT_GRAPH_MAX_EDGES, 1, 2_000);
        List<String> cleanSubfields = cleanList(subfields);
        String cleanQuery = blankToNull(query);
        if (records == CausalProjectionBuilder.DEFAULT_GRAPH_MIN_RECORDS && papers == null
                && diversity == CausalProjectionBuilder.DEFAULT_GRAPH_MIN_DIVERSITY
                && cleanSubfields.isEmpty() && cleanQuery == null
                && nodesLimit == CausalProjectionBuilder.DEFAULT_GRAPH_MAX_NODES
                && edgesLimit == CausalProjectionBuilder.DEFAULT_GRAPH_MAX_EDGES) {
            return refresher.defaultGraph();
        }
        refresher.summary();
        var rows = store.queryEdges(new GraphQuery(records, papers, diversity, cleanSubfields, cleanQuery, edgesLimit));
        return CausalProjectionBuilder.buildGraph(rows, nodesLimit);
    }

    public NodeDetail variableDetail(String variable) {
        String name = requireText(variable);
        List<GraphEdge> outgoing = facts.listOutgoingEdges(name, NODE_EDGE_LIMIT).stream().map(CausalEnricher::toEdge).toList();
        List<GraphEdge> incoming = facts.listIncomingEdges(name, NODE_EDGE_LIMIT).stream().map(CausalEnricher::toEdge).toList();
        long asCause = outgoing.stream().mapToLong(GraphEdge::recordCount).sum();
        long asEffect = incoming.stream().mapToLong(GraphEdge::recordCount).sum();
        if (asCause + asEffect == 0) throw new CausalNotFound("VARIABLE_NOT_FOUND", "未找到该因果变量");
        List<String> workIds = facts.listWorkIdsForVariable(name);
        Set<String> uniqueWorks = new HashSet<>(workIds);
        var meta = store.loadWorkMeta(uniqueWorks);
        var topics = store.loadWorkTopics(uniqueWorks);
        Map<String, Long> subfieldCounts = CausalEnricher.countSubfields(workIds, topics);
        Map<String, Long> yearCounts = CausalEnricher.countYears(workIds, meta);
        String dominant = subfieldCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未标注");
        var node = new GraphNode(name, name, asCause + asEffect, dominant, asCause, asEffect);
        return new NodeDetail(node, subfieldCounts, yearCounts, asCause + asEffect, outgoing, incoming);
    }

    public EdgeDetail edgeDetailByClaimId(long claimId) {
        EdgeAggregate edge = facts.findEdgeByClaimId(claimId);
        if (edge == null) throw new CausalNotFound("RELATION_NOT_FOUND", "未找到该因果关系");
        return edgeDetail(edge, facts.listClaimsByClaimId(claimId, DETAIL_CLAIM_LIMIT));
    }

    public EdgeDetail edgeDetailByCauseEffect(String cause, String effect) {
        String c = requireText(cause);
        String e = requireText(effect);
        EdgeAggregate edge = facts.findEdgeByCauseEffect(c, e);
        if (edge == null) throw new CausalNotFound("RELATION_NOT_FOUND", "暂无该因果关系的证据记录");
        return edgeDetail(edge, facts.listClaimsByCauseEffect(c, e, DETAIL_CLAIM_LIMIT));
    }

    public PaperDetail paperDetail(String workId) {
        String id = requireText(workId);
        List<RawClaimRow> rawClaims = facts.listClaimsByWorkId(id);
        var metaMap = store.loadWorkMeta(List.of(id));
        var topicMap = store.loadWorkTopics(List.of(id));
        var claims = CausalEnricher.enrichClaims(rawClaims, metaMap, topicMap);
        WorkMeta meta = metaMap.get(id);
        WorkTopicRow topic = topicMap.getOrDefault(id, List.of()).stream().findFirst().orElse(null);
        PaperInfo paper = new PaperInfo(id, meta == null ? null : meta.paperId(),
                meta == null ? null : meta.title(), meta == null ? null : meta.publicationYear(),
                meta == null ? null : meta.sourceId(), meta == null ? null : meta.sourceName(),
                topic == null ? null : topic.topicName(), topic == null ? null : topic.subfieldName());
        return new PaperDetail(paper, claims, CausalEnricher.buildPaperGraph(claims));
    }

    public PaperSummary paperSummary(String workId) {
        String id = requireText(workId);
        PaperSummaryRow row = facts.findPaperSummary(id);
        long records = row == null ? 0L : row.claimRecordCount();
        return new PaperSummary(id, records, row == null ? 0L : row.standardClaimCount(),
                row == null ? 0L : row.variableCount(), records > 0);
    }

    public List<NodeSearchHit> searchVariables(String query, Integer limit) {
        String q = requireText(query);
        int max = clamp(limit, 10, 1, 50);
        refresher.summary();
        return store.searchVariables(q, max).stream()
                .map(row -> new NodeSearchHit(row.name(), row.count())).toList();
    }

    public List<String> searchTerms(String query, Integer limit) {
        String q = requireText(query);
        int max = clamp(limit, 10, 1, 50);
        List<String> terms = stwVocabulary.search(q, max);
        if (!terms.isEmpty()) return terms;
        return searchVariables(q, max).stream().map(NodeSearchHit::variable).toList();
    }

    public List<PaperSearchHit> searchPapers(String query, Integer limit) {
        String q = requireText(query);
        int max = clamp(limit, 10, 1, 50);
        Map<String, Long> counts = new HashMap<>();
        for (NamedCountRow row : facts.listPaperClaimCounts()) counts.put(row.name(), row.count());
        if (counts.isEmpty()) return List.of();
        return store.searchWorkMeta(q, counts.keySet(), max).stream()
                .map(m -> new PaperSearchHit(m.workId(), m.paperId(), m.title(), m.publicationYear(),
                        m.sourceName(), counts.getOrDefault(m.workId(), 0L)))
                .sorted(Comparator.comparingLong(PaperSearchHit::claimRecordCount).reversed()
                        .thenComparing(PaperSearchHit::workId))
                .limit(max).toList();
    }

    private EdgeDetail edgeDetail(EdgeAggregate edge, List<RawClaimRow> rawClaims) {
        Set<String> workIds = new LinkedHashSet<>(edge.workIds());
        rawClaims.forEach(c -> workIds.add(c.workId()));
        return CausalEnricher.edgeDetail(edge, rawClaims, store.loadWorkMeta(workIds), store.loadWorkTopics(workIds));
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value) {
        String text = blankToNull(value);
        if (text == null || text.length() > 255) throw new IllegalArgumentException("查询参数不能为空且最多 255 个字符");
        return text;
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
