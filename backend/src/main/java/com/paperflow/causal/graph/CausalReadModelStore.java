package com.paperflow.causal.graph;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CausalReadModelStore {
    record EdgeIndexRow(
            long claimId, String causeStandard, String effectStandard,
            long recordCount, long paperCount, long diversity,
            long positiveCount, long negativeCount, long nullCount, long mixedCount,
            List<String> subfields, long revision
    ) {}

    record SnapshotRow(String key, long revision, String payload, Instant refreshedAt) {}

    record WorkMeta(
            String workId, String paperId, String title, Integer publicationYear, String sourceId, String sourceName
    ) {}

    record WorkTopicRow(String workId, String subfieldName, String topicName) {}

    record GraphQuery(
            int minRecordCount, Integer minPaperCount, int minDiversity,
            List<String> subfields, String query, int limit
    ) {}

    SnapshotRow findSnapshot(String key);
    void replaceReadModel(List<EdgeIndexRow> edges, List<SnapshotRow> snapshots);
    List<EdgeIndexRow> queryEdges(GraphQuery query);
    List<String> listKnownSubfields();
    Map<String, WorkMeta> loadWorkMeta(Collection<String> workIds);
    Map<String, List<WorkTopicRow>> loadWorkTopics(Collection<String> workIds);
    List<WorkMeta> searchWorkMeta(String query, Collection<String> candidateWorkIds, int limit);
}
