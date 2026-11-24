package com.paperflow.client;

import com.paperflow.client.adapters.engine.LegacyCitationsRetrievalEngine;
import com.paperflow.client.citation.CitationEngine.EngineFailure;
import com.paperflow.client.citation.model.CitationStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paperflow.client.EngineHttpFixture.*;
import static org.assertj.core.api.Assertions.*;

class LegacyCitationsRetrievalEngineTest {
    @Test
    void mapsAllCandidatesAndProgressWithUnrestrictedSources() throws Exception {
        try (var server = new EngineHttpFixture()) {
            server.route("/health", exchange -> reply(exchange, 200, "{\"status\":\"ok\"}"));
            server.route("/api/citation-jobs", exchange -> {
                var policy = JSON.readTree(exchange.getRequestBody()).path("retrieval_policy");
                var sourceIds = policy.path("source_ids");
                reply(exchange, !sourceIds.isArray() ? 201 : 422, "{\"job_id\":\"job_1\"}");
            });
            var polls = new AtomicInteger();
            server.route("/api/citation-jobs/job_1", exchange -> reply(exchange, 200,
                    polls.getAndIncrement() == 0 ? "{\"status\":\"running\"}" : success()));
            server.route("/api/citation-jobs/job_1/events", exchange -> reply(exchange, 200, """
                    [{"event_type":"node_started","node":"rank_evidence","message":"Ranking","progress":0.1}]
                    """));
            var stages = new ArrayList<CitationStage>();
            var engine = engine(server);
            assertThat(engine.isHealthy()).isTrue();
            var result = engine.execute(REQUEST, update -> {
                stages.add(update.stage());
                assertThat(update.progress()).isEqualTo(0.75);
            }, () -> false);
            assertThat(stages).containsExactly(CitationStage.RANK_EVIDENCE);
            assertThat(result.referencesByStatement().get("c1")).hasSize(2);
            var candidate = result.referencesByStatement().get("c1").get(1);
            assertThat(candidate.workId()).isEqualTo("W2");
            assertThat(candidate.chunks().get(0).supportScore()).isEqualTo(0.8);
            assertThat(result.selectedReferenceIds().get("c1")).containsExactly("r1");
            assertThat(result.evidenceStats().get("c1").rejected().lowScore()).isEqualTo(2);
        }
    }

    @Test
    void mapsFailureAndInterruptsPollingOnCancellation() throws Exception {
        try (var server = new EngineHttpFixture()) {
            server.route("/api/citation-jobs", exchange -> reply(exchange, 201, "{\"job_id\":\"job_1\"}"));
            server.route("/api/citation-jobs/job_1", exchange -> reply(exchange, 200,
                    "{\"status\":\"failed\",\"errors\":[\"private traceback\"]}"));
            server.route("/api/citation-jobs/job_1/events", exchange -> reply(exchange, 200, """
                    [{"event_type":"node_started","node":"parse_text","message":"Parsing"}]
                    """));
            var engine = engine(server);
            assertThatExceptionOfType(EngineFailure.class)
                    .isThrownBy(() -> engine.execute(REQUEST, update -> {}, () -> false))
                    .satisfies(error -> assertThat(error.code()).isEqualTo("CITATION_ENGINE_FAILED"));
            var cancelled = new AtomicBoolean();
            assertThatExceptionOfType(InterruptedException.class)
                    .isThrownBy(() -> engine.execute(REQUEST, update -> cancelled.set(true), cancelled::get));
        }
    }

    private LegacyCitationsRetrievalEngine engine(EngineHttpFixture server) {
        return new LegacyCitationsRetrievalEngine(server.url(), Duration.ofSeconds(3), Duration.ofMillis(10), JSON);
    }

    private String success() {
        return """
                {"status":"succeeded","claims":[{"claim_id":"c1","text":"Trade grows.","start_char":0,"end_char":12}],
                 "supported_references":{"c1":[{"reference_id":"r1","title":"Example"},
                   {"reference_id":"r2","title":"Candidate","metadata":{"work_id":"W2"},"chunks":[
                     {"chunk_id":"ch","chunk_text":"Trade","metadata":{"evidence_score":0.8,"evidence_confidence":0.9}}]}]},
                 "selected_references":{"c1":[{"reference_id":"r1"}]},
                 "evidence_stats":{"c1":{"judged_chunks":3,"supported_chunks":1,"supported_references":2,
                   "rejected_chunks":{"low_score":2}}}}
                """;
    }
}
