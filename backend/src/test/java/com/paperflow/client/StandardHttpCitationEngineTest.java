package com.paperflow.client;

import com.paperflow.client.adapters.engine.StandardHttpCitationEngine;
import com.paperflow.client.citation.CitationEngine.EngineFailure;
import com.paperflow.client.citation.CitationEngine.ProgressSink;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationUpdate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.paperflow.client.EngineHttpFixture.*;
import static org.assertj.core.api.Assertions.*;

class StandardHttpCitationEngineTest {
    @Test
    void submitsAndMapsStandardResultAfterProgress() throws Exception {
        try (var server = new EngineHttpFixture()) {
            var submitted = new AtomicReference<String>();
            server.route("/v1/health", exchange -> reply(exchange, 200, "{\"status\":\"ok\"}"));
            server.route("/v1/citation-runs", exchange -> {
                submitted.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                reply(exchange, 202, "{\"engineRunId\":\"run_1\"}");
            });
            var polls = new AtomicInteger();
            server.route("/v1/citation-runs/run_1", exchange -> reply(exchange, 200, polls.getAndIncrement() == 0
                    ? "{\"status\":\"RUNNING\",\"stage\":\"PARSE_TEXT\",\"progress\":0.2}" : """
                    {"status":"SUCCEEDED","result":{"statements":[{"statementId":"s1","text":"Trade grows.",
                    "needsCitation":true,"startChar":0,"endChar":12}],
                    "initialSelectedReferenceIds":{"s1":["r1"]},"warnings":["example"]}}
                    """));
            var seen = new ArrayList<CitationStage>();
            var engine = engine(server);
            assertThat(engine.isHealthy()).isTrue();
            var result = engine.execute(REQUEST, update -> seen.add(update.stage()), () -> false);
            assertThat(JSON.readTree(submitted.get()).path("runId").asText()).isEqualTo(REQUEST.runId().toString());
            assertThat(seen).containsExactly(CitationStage.PARSE_TEXT);
            assertThat(result.statements().get(0).statementId()).isEqualTo("s1");
            assertThat(result.selectedReferenceIds().get("s1")).containsExactly("r1");
            assertThat(result.warnings()).containsExactly("example");
        }
    }

    @Test
    void sendsDeleteWhenCancelled() throws Exception {
        try (var server = new EngineHttpFixture()) {
            server.route("/v1/citation-runs", exchange -> reply(exchange, 202, "{\"engineRunId\":\"run_2\"}"));
            var deleted = new AtomicBoolean();
            server.route("/v1/citation-runs/run_2", exchange -> {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    deleted.set(true);
                    reply(exchange, 204, "");
                } else {
                    reply(exchange, 200, "{\"status\":\"RUNNING\",\"stage\":\"PARSE_TEXT\",\"progress\":0.2}");
                }
            });
            var cancelled = new AtomicBoolean();
            assertThatExceptionOfType(InterruptedException.class)
                    .isThrownBy(() -> engine(server).execute(REQUEST, update -> cancelled.set(true), cancelled::get));
            assertThat(deleted).isTrue();
        }
    }

    @Test
    void timesOutWhenTheEngineSendsHeadersButStallsItsResponseBody() throws Exception {
        try (var server = new EngineHttpFixture()) {
            server.route("/v1/citation-runs", exchange -> {
                exchange.sendResponseHeaders(202, 0);
                try {
                    Thread.sleep(1100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.getResponseBody().write("{\"engineRunId\":\"late\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            var engine = new StandardHttpCitationEngine(server.url(), Duration.ofMillis(500), Duration.ofMillis(10), JSON);
            var submitted = new AtomicBoolean();
            var sink = new ProgressSink() {
                @Override
                public void update(CitationUpdate update) {}

                @Override
                public void runStarted(String engineRunId) {
                    submitted.set(true);
                }
            };
            assertThatExceptionOfType(EngineFailure.class)
                    .isThrownBy(() -> engine.execute(REQUEST, sink, () -> false))
                    .satisfies(error -> assertThat(error.code()).isEqualTo("CITATION_ENGINE_TIMEOUT"));
            assertThat(submitted).isFalse();
        }
    }

    private StandardHttpCitationEngine engine(EngineHttpFixture server) {
        return new StandardHttpCitationEngine(server.url(), Duration.ofSeconds(3), Duration.ofMillis(10), JSON);
    }
}
