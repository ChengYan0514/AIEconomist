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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.paperflow.integration.WorkspaceFixture.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OriginalPreflightHttpTest {
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

    @BeforeEach
    void signIn() throws Exception {
        http = new WorkspaceHttp(mvc, json);
        admin = http.login("integration", "workspace-test-password");
    }

    @Test
    void reviewsMixedPreflightAndConfirmsOnlyTheImportableRow() throws Exception {
        var batch = preflight("""
                S123,2025,"Importable title
                on two lines",Alice,files/paper.pdf

                S123,2024,Legacy paper,Alice;Bob,files/paper.pdf
                S123,2025,Missing original,Alice,files/missing.pdf
                S123,invalid,Invalid metadata,Alice,files/paper.pdf
                S123,2025,Importable title on two lines,Alice,files/paper.pdf
                """);
        String id = batch.required("jobId").asText();
        assertThat(batch.required("ready").asInt()).isEqualTo(1);
        assertThat(batch.required("skipped").asInt()).isEqualTo(2);
        assertThat(batch.required("failed").asInt()).isEqualTo(2);
        JsonNode preview = items(id, "");
        assertReview(preview.get(0), 1, "READY", "IMPORTABLE", "files/paper.pdf", null, null);
        assertReview(preview.get(1), 2, "SKIPPED", "DUPLICATE", "files/paper.pdf", "论文已存在", "legacy-001");
        assertReview(preview.get(2), 3, "FAILED", "ERROR", "files/missing.pdf", "包内找不到该原件", null);
        assertReview(preview.get(3), 4, "FAILED", "ERROR", "files/paper.pdf", "发表年份应为四位数字", null);
        assertReview(preview.get(4), 5, "SKIPPED", "DUPLICATE", "files/paper.pdf", "与包内其他行重复", null);
        assertThat(preview.get(1).required("result")).isEqualTo(json.createObjectNode().put("paperId", "legacy-001"));
        assertThat(preview.get(2).required("result").isNull()).isTrue();
        assertThat(preview.get(2).required("inputError").asBoolean()).isTrue();
        assertThat(items(id, "SKIPPED").get(0)).isEqualTo(preview.get(1));
        mvc.perform(get("/api/papers/{id}", "legacy-001").session(admin)).andExpect(status().isOk());

        assertThat(confirm(id).required("jobId").asText()).isEqualTo(id);
        JsonNode finished = await(id, "PARTIAL");
        assertThat(confirm(id)).isEqualTo(finished);
        assertThat(finished.required("succeeded").asInt()).isEqualTo(1);
        assertThat(finished.required("canRetry").asBoolean()).isFalse();
        JsonNode imported = items(id, "SUCCEEDED").get(0);
        assertThat(imported.required("preflight")).isEqualTo(preview.get(0).required("preflight"));
        assertThat(imported.required("result")).isEqualTo(json.createObjectNode().put("paperId", imported.required("paperId").asText()));
        assertThat(items(id, "SKIPPED").get(0)).isEqualTo(preview.get(1));
        assertThat(items(id, "FAILED").get(0)).isEqualTo(preview.get(2));
    }

    @Test
    void readsPreflightRowsSavedBeforePathsWereRecorded() throws Exception {
        UUID stored = UUID.randomUUID();
        jdbc.update("INSERT INTO v2_job (job_id, kind, username, confirmed_at) VALUES (?, 'ORIGINAL_IMPORT', 'integration', now())", stored);
        jdbc.update("""
                INSERT INTO v2_job_item (job_id, kind, sequence, target, paper_id, state, message, result, finished_at)
                VALUES (?, 'ORIGINAL_IMPORT', 1, 'Legacy paper', 'legacy-001', 'SKIPPED', '论文已存在', '{"paperId":"legacy-001"}', now()),
                       (?, 'ORIGINAL_IMPORT', 2, 'Old missing original', NULL, 'FAILED', '包内找不到该原件', NULL, now())
                """, stored, stored);
        String id = stored.toString();
        var items = items(id, "");
        assertReview(items.get(0), 1, "SKIPPED", "DUPLICATE", null, "论文已存在", "legacy-001");
        assertReview(items.get(1), 2, "FAILED", "ERROR", null, "包内找不到该原件", null);
        assertThat(items.get(0).required("result")).isEqualTo(json.createObjectNode().put("paperId", "legacy-001"));
        assertThat(items.get(1).required("result").isNull()).isTrue();
        assertThat(items.get(1).required("inputError").asBoolean()).isTrue();
        assertThat(job(id).required("canRetry").asBoolean()).isFalse();
        mvc.perform(http.write(post("/api/jobs/{id}/retry", id), admin, null)).andExpect(status().isConflict());
    }

    @Test
    void rechecksWhenAPaperIsRegisteredAfterPreflight() throws Exception {
        var batch = preflight("S123,2025,Registered after preflight,Alice,files/paper.pdf\n");
        String id = batch.required("jobId").asText();
        var file = new MockMultipartFile("file", "paper.pdf", "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII));
        mvc.perform(http.write(multipart("/api/papers").file(file).param("title", "Registered after preflight")
                        .param("sourceId", "S123").param("year", "2025").param("authors", "Alice"), admin, null))
                .andExpect(status().isCreated());
        confirm(id);
        assertThat(await(id, "SUCCEEDED").required("skipped").asInt()).isEqualTo(1);
        var skipped = items(id, "SKIPPED").get(0);
        assertReview(skipped, 1, "SKIPPED", "IMPORTABLE", "files/paper.pdf", null, null);
        assertThat(skipped.required("message").asText()).isEqualTo("论文已存在");
        assertThat(skipped.required("result").isNull()).isTrue();
    }

    private JsonNode preflight(String rows) throws Exception {
        byte[] zip = WorkspaceFixture.zip("batch.csv", "source_id,year,paper_title,authors,file_path\n" + rows,
                "files/paper.pdf", PDF);
        var upload = multipart("/api/imports").file(new MockMultipartFile("file", "preflight.zip", "application/zip", zip));
        return http.body(mvc.perform(http.write(upload, admin, null)).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode items(String id, String state) throws Exception {
        return http.body(mvc.perform(get("/api/jobs/{id}/items", id).session(admin).param("state", state))
                .andExpect(status().isOk()).andReturn()).required("items");
    }

    private JsonNode confirm(String id) throws Exception {
        return http.body(mvc.perform(http.write(post("/api/imports/{id}/confirm", id), admin, null))
                .andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode await(String id, String state) throws Exception {
        JsonNode result = job(id);
        for (int attempt = 0; attempt < 100 && !result.required("state").asText().equals(state); attempt++) {
            Thread.sleep(50);
            result = job(id);
        }
        assertThat(result.required("state").asText()).as("Task summary: %s", result).isEqualTo(state);
        return result;
    }

    private JsonNode job(String id) throws Exception {
        return http.body(mvc.perform(get("/api/jobs/{id}", id).session(admin)).andExpect(status().isOk()).andReturn());
    }

    private void assertReview(JsonNode item, int sequence, String state, String status, String path, String reason,
                              String existingPaperId) {
        assertThat(item.required("sequence").asInt()).isEqualTo(sequence);
        assertThat(item.required("state").asText()).isEqualTo(state);
        assertThat(item.required("preflight")).isEqualTo(json.createObjectNode().put("status", status)
                .put("originalPath", path).put("reason", reason).put("existingPaperId", existingPaperId));
    }
}
