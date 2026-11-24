package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.main.Paperflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Paperflow.class, properties = "paperflow.causal.enabled=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class CausalExtractionHttpTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private WorkspaceHttp http;
    private MockHttpSession admin;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @BeforeEach void login() throws Exception {
        http = new WorkspaceHttp(mvc, json);
        admin = http.login("integration", "workspace-test-password");
    }

    @Test
    void estimatesExtractsPublishesIdempotentlyDownloadsArtifactsAndRetriesFailures() throws Exception {
        fixture.causalEngine().failOnceFor("W009");
        var req = Map.of("target", Map.of("mode", "PAPER_IDS", "paperIds", List.of("legacy-001")),
                "ingestionMode", "STANDARDIZED");

        mvc.perform(http.write(post("/api/causal/extractions/estimate"), admin, json.valueToTree(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.executableCount").value(1))
                .andExpect(jsonPath("$.ingestionMode").value("STANDARDIZED"));

        JsonNode created = http.body(mvc.perform(http.write(post("/api/causal/extractions"), admin, json.valueToTree(req)))
                .andExpect(status().isAccepted()).andReturn());
        String batchId = created.path("batchId").asText();

        JsonNode failedDetail = awaitBatchStatus(batchId, "FAILED");
        assertThat(failedDetail.path("runs").get(0).path("errorCode").asText()).isEqualTo("MODEL_TIMEOUT");

        mvc.perform(http.write(post("/api/causal/extractions/{id}/retry", batchId), admin, null))
                .andExpect(status().isAccepted());
        JsonNode succeededDetail = awaitBatchStatus(batchId, "SUCCEEDED");
        String runId = succeededDetail.path("runs").get(0).path("runId").asText();
        assertThat(succeededDetail.path("batch").path("totalClaims").asInt()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM causal_publication_receipt WHERE publication_id = ?",
                Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM causal_dataset_revision WHERE id = 1", Long.class))
                .isGreaterThanOrEqualTo(2L);

        mvc.perform(get("/api/causal/papers/{workId}", "W009").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims.length()").value(3));

        mvc.perform(get("/api/causal/runs/{runId}/artifacts", runId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("stage1_raw_responses.jsonl"));
        mvc.perform(get("/api/causal/runs/{runId}/artifacts/{name}", runId, "stage1_raw_responses.jsonl").session(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/causal/runs/{runId}/artifacts/{name}", runId, "secret.env").session(admin))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/system-status").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.dependency == 'CAUSAL_ENGINE')].state").value("HEALTHY"))
                .andExpect(jsonPath("$.configuration.causalEnabled").value(true));
    }

    @Test
    void extractsV2ContentBlocksHandlesZeroClaimsCancelsAndRejectsLegacyDuplicateRelations() throws Exception {
        String zeroPaper = seedV2PublishedPaper("W-ZERO", "Zero Claim Study");
        fixture.causalEngine().zeroClaimsFor("W-ZERO");
        var zeroReq = Map.of("target", Map.of("mode", "PAPER_IDS", "paperIds", List.of(zeroPaper)),
                "ingestionMode", "STANDARDIZED");
        String zeroBatch = http.body(mvc.perform(http.write(post("/api/causal/extractions"), admin, json.valueToTree(zeroReq)))
                .andExpect(status().isAccepted()).andReturn()).path("batchId").asText();
        JsonNode zeroDetail = awaitBatchStatus(zeroBatch, "SUCCEEDED");
        assertThat(zeroDetail.path("batch").path("zeroClaimPapers").asInt()).isEqualTo(1);
        assertThat(zeroDetail.path("batch").path("totalClaims").asInt()).isEqualTo(0);
        JsonNode exported = fixture.causalEngine().lastRequest("W-ZERO").path("blocks");
        assertThat(exported).hasSize(2);
        assertThat(exported.get(0).path("titleLevel").asInt()).isEqualTo(2);

        String cancelPaper = seedV2PublishedPaper("W-CANCEL", "Cancellable Study");
        fixture.causalEngine().holdFor("W-CANCEL");
        var cancelReq = Map.of("target", Map.of("mode", "PAPER_IDS", "paperIds", List.of(cancelPaper)),
                "ingestionMode", "RAW");
        String cancelBatch = http.body(mvc.perform(http.write(post("/api/causal/extractions"), admin, json.valueToTree(cancelReq)))
                .andExpect(status().isAccepted()).andReturn()).path("batchId").asText();
        mvc.perform(http.write(post("/api/causal/extractions/{id}/cancel", cancelBatch), admin, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true));
        awaitBatchStatus(cancelBatch, "CANCELLED");

        String dupPaper = seedV2PublishedPaper("W-DUP", "Duplicate Relation Study");
        jdbc.update("""
                INSERT INTO paper_claim_table (paper_id, claim_id, sign_of_impact, type_of_relationship,
                    claim, cause, effect, evidence, causal_inference_method, is_main_contribution)
                VALUES ('W-DUP', 17, 'negative', 'causal', 'dup1', 'minimum wage hike', 'teen employment', 'ev1', 'DID', true),
                       ('W-DUP', 17, 'negative', 'causal', 'dup2', 'minimum wage hike', 'teen employment', 'ev2', 'DID', false)
                """);
        var dupReq = Map.of("target", Map.of("mode", "PAPER_IDS", "paperIds", List.of(dupPaper)),
                "ingestionMode", "STANDARDIZED");
        String dupBatch = http.body(mvc.perform(http.write(post("/api/causal/extractions"), admin, json.valueToTree(dupReq)))
                .andExpect(status().isAccepted()).andReturn()).path("batchId").asText();
        JsonNode dupDetail = awaitBatchStatus(dupBatch, "FAILED");
        assertThat(dupDetail.path("runs").get(0).path("errorCode").asText()).isEqualTo("LEGACY_DUPLICATE_RELATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_claim_table WHERE paper_id = 'W-DUP'", Integer.class))
                .isEqualTo(2);
    }

    private String seedV2PublishedPaper(String workId, String title) {
        String paperId = String.format("%064x", Math.abs((long) workId.hashCode()) + 1000L);
        java.util.UUID parseId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, publication_year, title, authors,
                    original_name, original_path, original_type, original_size, file_revision, matched_work_id)
                VALUES (?, false, 'S123', 2024, ?, 'Tester', 'p.pdf', 'v2/p.pdf', 'PDF', 100, 1, ?)
                """, paperId, title, workId);
        jdbc.update("""
                INSERT INTO v2_parse_artifact (parse_id, paper_id, file_revision, legacy, storage_path, content_path)
                VALUES (?, ?, 1, false, 'v2/parse', 'v2/parse/content.json')
                """, parseId, paperId);
        jdbc.update("UPDATE v2_paper SET latest_parse_id = ?, published_parse_id = ? WHERE paper_id = ?",
                parseId, parseId, paperId);
        jdbc.update("""
                INSERT INTO v2_content_block (parse_id, sequence, block_type, body, page, attributes)
                VALUES (?, 0, 'title', 'Introduction', 0, '{"level": 2}'::jsonb),
                       (?, 1, 'discarded', 'Header noise', 0, '{}'::jsonb),
                       (?, 2, 'text', 'Empirical findings on minimum wage.', 1, '{}'::jsonb)
                """, parseId, parseId, parseId);
        return paperId;
    }

    private JsonNode awaitBatchStatus(String batchId, String expected) throws Exception {
        JsonNode detail = null;
        for (int i = 0; i < 60; i++) {
            detail = http.body(mvc.perform(get("/api/causal/extractions/{id}", batchId).session(admin))
                    .andExpect(status().isOk()).andReturn());
            if (expected.equals(detail.path("batch").path("status").asText())) return detail;
            Thread.sleep(80);
        }
        assertThat(detail.path("batch").path("status").asText()).isEqualTo(expected);
        return detail;
    }
}
