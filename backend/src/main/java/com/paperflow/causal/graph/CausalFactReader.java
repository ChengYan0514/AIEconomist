package com.paperflow.causal.graph;

import java.math.BigDecimal;
import java.util.List;

public interface CausalFactReader {
    record EdgeProjectionRow(
            long claimId, String causeStandard, String effectStandard,
            long recordCount, long paperCount, long diversity,
            long positiveCount, long negativeCount, long nullCount, long mixedCount,
            List<String> workIds
    ) {}

    record PaperClaimPair(String workId, long claimId, String causeStandard, String effectStandard, long recordCount) {}

    record RawClaimRow(
            long recordId, String workId, long claimId, String claim, String cause, String effect,
            String causeStandard, String effectStandard, String signOfImpact, String typeOfRelationship,
            String causalInferenceMethod, String evidenceMethodOtherDescription, Boolean isMainContribution,
            String levelOfTentativeness, String sourcesOfExogenousVariation, String statisticalSignificance,
            BigDecimal causeScore, BigDecimal effectScore, String evidence
    ) {}

    record EdgeAggregate(
            long claimId, String causeStandard, String effectStandard,
            long recordCount, long paperCount, long diversity,
            long positiveCount, long negativeCount, long nullCount, long mixedCount,
            List<String> methods, List<String> workIds
    ) {}

    record OverviewStats(
            long totalClaimRecords, long totalStandardClaims, long totalPapers,
            long totalNodes, long graphNodes, long graphEdges
    ) {}

    record NamedCountRow(String name, long count) {}

    record GlobalRelationRow(String cause, String effect, long claimRecordCount, long paperCount, long methodCount) {}

    record PaperSummaryRow(long claimRecordCount, long standardClaimCount, long variableCount) {}

    long datasetRevision();
    OverviewStats overview(int minRecordCount);
    List<String> listMethods();
    List<EdgeProjectionRow> listAllEdgeProjections();
    List<PaperClaimPair> listPaperClaimPairs();
    List<NamedCountRow> listGlobalMethodCounts();
    List<NamedCountRow> listGlobalVariableCounts(int limit);
    List<GlobalRelationRow> listGlobalRelations(int limit);
    EdgeAggregate findEdgeByClaimId(long claimId);
    EdgeAggregate findEdgeByCauseEffect(String cause, String effect);
    List<EdgeAggregate> listOutgoingEdges(String variable, int limit);
    List<EdgeAggregate> listIncomingEdges(String variable, int limit);
    List<String> listWorkIdsForVariable(String variable);
    List<RawClaimRow> listClaimsByClaimId(long claimId, int limit);
    List<RawClaimRow> listClaimsByCauseEffect(String cause, String effect, int limit);
    List<RawClaimRow> listClaimsByWorkId(String workId);
    PaperSummaryRow findPaperSummary(String workId);
    List<NamedCountRow> listPaperClaimCounts();
}
