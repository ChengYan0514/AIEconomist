package com.paperflow.causal.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CausalGraphModels {
    private CausalGraphModels() {}

    public record Overview(
            long totalClaimRecords, long totalStandardClaims, long totalPapers,
            long totalNodes, long totalEdges, long graphNodes, long graphEdges, int defaultMinRecordCount
    ) {}

    public record DatasetVersion(long revision, String label, Instant refreshedAt) {}

    public record GraphSummary(
            Overview overview, List<String> subfields, List<String> methods, DatasetVersion version
    ) {}

    public record GraphNode(
            String id, String label, long occurrences, String dominantSubfield, long asCauseCount, long asEffectCount
    ) {}

    public record GraphEdge(
            long claimId, String source, String target, long recordCount, long paperCount,
            long diversity, double disagreement, String dominantSignCategory, Map<String, Long> signBreakdown
    ) {}

    public record GraphData(List<GraphNode> nodes, List<GraphEdge> edges) {}

    public record NodeDetail(
            GraphNode node, Map<String, Long> subfieldCounts, Map<String, Long> yearCounts,
            long totalClaims, List<GraphEdge> outgoing, List<GraphEdge> incoming
    ) {}

    public record EdgeStats(long subfieldCount, long topicCount, int yearSpan, List<String> methods) {}

    public record ClaimItem(
            long recordId, String workId, String paperId, String title, Integer publicationYear,
            String sourceId, String sourceName, String topicName, String subfieldName,
            long claimId, String claim, String cause, String effect,
            String causeStandard, String effectStandard,
            String signOfImpact, String signCategory, String typeOfRelationship,
            String causalInferenceMethod, String evidenceMethodOtherDescription,
            Boolean isMainContribution, String levelOfTentativeness,
            String sourcesOfExogenousVariation, String statisticalSignificance,
            BigDecimal causeScore, BigDecimal effectScore, String evidence
    ) {}

    public record EdgeDetail(GraphEdge edge, EdgeStats stats, List<ClaimItem> claims) {}

    public record PaperInfo(
            String workId, String paperId, String title, Integer publicationYear,
            String sourceId, String sourceName, String topicName, String subfieldName
    ) {}

    public record PaperDetail(PaperInfo paper, List<ClaimItem> claims, GraphData paperGraph) {}

    public record PaperSummary(
            String workId, long claimRecordCount, long standardClaimCount, long variableCount, boolean hasClaims
    ) {}

    public record FieldItem(String subfield, String topic, long claimRecordCount, long paperCount, long variableCount) {}

    public record NamedCount(String name, long count) {}

    public record GlobalRelation(String cause, String effect, long claimRecordCount, long paperCount, long methodCount) {}

    public record FieldInsights(
            List<NamedCount> methodCounts, List<NamedCount> topVariables, List<GlobalRelation> topRelations
    ) {}

    public record FieldAnalysis(List<FieldItem> items, FieldInsights insights) {}

    public record NodeSearchHit(String variable, long occurrences) {}

    public record PaperSearchHit(
            String workId, String paperId, String title, Integer publicationYear, String sourceName, long claimRecordCount
    ) {}

    public record RefreshResult(long revision, long indexedEdges, Instant refreshedAt) {}
}
