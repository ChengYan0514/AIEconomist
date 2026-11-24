package com.paperflow.causal.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.graph.CausalGraphModels.*;
import com.paperflow.causal.graph.CausalReadModelStore.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class CausalReadModelRefresher {
    public static final String KEY_SUMMARY = "SUMMARY";
    public static final String KEY_DEFAULT_GRAPH = "DEFAULT_GRAPH";
    public static final String KEY_FIELDS = "FIELDS";
    private static final Logger log = LoggerFactory.getLogger(CausalReadModelRefresher.class);

    private final CausalFactReader facts;
    private final CausalReadModelStore store;
    private final ObjectMapper json;
    private final Duration cacheTtl;
    private final Executor background;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicBoolean asyncScheduled = new AtomicBoolean();
    private final Map<String, CacheEntry<?>> memoryCache = new ConcurrentHashMap<>();

    public CausalReadModelRefresher(
            CausalFactReader facts, CausalReadModelStore store, ObjectMapper json, Duration cacheTtl, Executor background
    ) {
        this.facts = facts;
        this.store = store;
        this.json = json;
        this.cacheTtl = cacheTtl;
        this.background = background;
    }

    public GraphSummary summary() {
        return loadSnapshot(KEY_SUMMARY, GraphSummary.class);
    }

    public GraphData defaultGraph() {
        return loadSnapshot(KEY_DEFAULT_GRAPH, GraphData.class);
    }

    public FieldAnalysis fields() {
        return loadSnapshot(KEY_FIELDS, FieldAnalysis.class);
    }

    public void ensureReadyAsync() {
        long current = safeRevision();
        SnapshotRow snapshot = store.findSnapshot(KEY_SUMMARY);
        if (snapshot == null || snapshot.revision() != current) refreshAsync();
    }

    public void refreshAsync() {
        if (!asyncScheduled.compareAndSet(false, true)) return;
        background.execute(() -> {
            try {
                refreshNow();
            } catch (Exception error) {
                log.warn("Background causal read-model refresh failed", error);
            } finally {
                asyncScheduled.set(false);
            }
        });
    }

    public RefreshResult refreshNow() {
        refreshLock.lock();
        try {
            long revision = facts.datasetRevision();
            Instant now = Instant.now();
            var edgeRows = facts.listAllEdgeProjections();
            var pairs = facts.listPaperClaimPairs();
            Set<String> allWorks = new HashSet<>();
            edgeRows.forEach(r -> allWorks.addAll(r.workIds()));
            pairs.forEach(p -> allWorks.add(p.workId()));
            var topicsByWork = store.loadWorkTopics(allWorks);
            var indexedEdges = CausalProjectionBuilder.buildEdgeIndex(edgeRows, topicsByWork, revision);
            var subfields = collectSubfields(indexedEdges, store.listKnownSubfields());
            var summary = CausalProjectionBuilder.buildSummary(
                    facts.overview(CausalProjectionBuilder.OVERVIEW_MIN_RECORDS), subfields, facts.listMethods(), revision, now);
            var defaultEdges = indexedEdges.stream()
                    .filter(e -> e.recordCount() >= CausalProjectionBuilder.DEFAULT_GRAPH_MIN_RECORDS
                            && e.diversity() >= CausalProjectionBuilder.DEFAULT_GRAPH_MIN_DIVERSITY)
                    .limit(CausalProjectionBuilder.DEFAULT_GRAPH_MAX_EDGES)
                    .toList();
            var defaultGraph = CausalProjectionBuilder.buildGraph(defaultEdges, CausalProjectionBuilder.DEFAULT_GRAPH_MAX_NODES);
            var fields = CausalProjectionBuilder.buildFieldAnalysis(pairs, topicsByWork,
                    facts.listGlobalMethodCounts(), facts.listGlobalVariableCounts(10), facts.listGlobalRelations(8));
            store.replaceReadModel(indexedEdges, List.of(
                    new SnapshotRow(KEY_SUMMARY, revision, write(summary), now),
                    new SnapshotRow(KEY_DEFAULT_GRAPH, revision, write(defaultGraph), now),
                    new SnapshotRow(KEY_FIELDS, revision, write(fields), now)));
            cachePut(KEY_SUMMARY, revision, summary, now);
            cachePut(KEY_DEFAULT_GRAPH, revision, defaultGraph, now);
            cachePut(KEY_FIELDS, revision, fields, now);
            return new RefreshResult(revision, indexedEdges.size(), now);
        } finally {
            refreshLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T loadSnapshot(String key, Class<T> type) {
        long current = safeRevision();
        CacheEntry<?> cached = memoryCache.get(key);
        if (cached != null && cached.revision == current && cached.expiresAt.isAfter(Instant.now())) {
            return (T) cached.value;
        }
        SnapshotRow row = store.findSnapshot(key);
        if (row == null) {
            refreshNow();
            row = store.findSnapshot(key);
        } else if (row.revision() != current) {
            refreshAsync();
        }
        T parsed = read(row.payload(), type);
        cachePut(key, row.revision(), parsed, Instant.now());
        return parsed;
    }

    private List<String> collectSubfields(List<EdgeIndexRow> edges, List<String> known) {
        Set<String> merged = new TreeSet<>(known);
        edges.forEach(e -> merged.addAll(e.subfields()));
        return List.copyOf(merged);
    }

    private long safeRevision() {
        try {
            return facts.datasetRevision();
        } catch (Exception error) {
            log.warn("Unable to read causal dataset revision, using 0", error);
            return 0L;
        }
    }

    private void cachePut(String key, long revision, Object value, Instant now) {
        memoryCache.put(key, new CacheEntry<>(revision, value, now.plus(cacheTtl)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot serialize causal snapshot", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot parse causal snapshot", error);
        }
    }

    private record CacheEntry<T>(long revision, T value, Instant expiresAt) {}
}
