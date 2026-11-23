package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.main.Paperflow;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.paperflow.integration.WorkspaceFixture.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class WorkspaceTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private MockHttpSession session;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }
    @Test
    void administratorCanUseTheWorkspaceWithoutChangingLegacyData() throws Exception {
        var legacyBefore = WorkspaceFixture.legacyContents(jdbc);
        login();
        legacyPaperRemainsReadableAndEditsAreIsolated();
        String paper = upload("A Test Paper", "10.1/TEST");
        uploadBatch();
        parseAndPublish(paper);
        repeatPublishWithoutAnotherMutationAudit(paper);
        importAndMatch(paper);
        updateOriginal(paper);
        archiveAndRestore(paper);
        assertThat(WorkspaceFixture.legacyContents(jdbc)).isEqualTo(legacyBefore);
    }

    private void login() throws Exception {
        mvc.perform(get("/api/papers")).andExpect(status().isUnauthorized());
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) csrf.getRequest().getSession(false);
        write(post("/api/session"), Map.of("username", "integration", "password", "workspace-test-password"), 200);
        mvc.perform(get("/api/session").session(session)).andExpect(jsonPath("$.username").value("integration"));
    }

    private void legacyPaperRemainsReadableAndEditsAreIsolated() throws Exception {
        mvc.perform(get("/api/papers").session(session).param("status", "PUBLISHED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/papers/legacy-001/blocks").session(session))
                .andExpect(jsonPath("$.items[0].text").value("Existing published text"));
        mvc.perform(get("/api/papers/legacy-001/file").session(session)).andExpect(content().contentType("application/pdf"));
        var edit = Map.of("sourceId", "S123", "year", 2024, "title", "Updated legacy title", "authors", "Alice;Bob", "version", 0);
        var edited = write(patch("/api/papers/legacy-001"), edit, 200);
        assertThat(edited.path("matchedWorkId").asText()).isEqualTo("W009");
        assertThat(edited.path("paperId").asText()).isEqualTo("legacy-001");
        assertThat(jdbc.queryForObject("SELECT paper_title FROM original_file", String.class)).isEqualTo("Legacy paper");
        assertThat(auditSummary("PAPER_EDIT", "legacy-001", "SUCCEEDED").path("changedFields").toString())
                .contains("title").doesNotContain("Updated legacy title");
        write(patch("/api/papers/legacy-001"), edit, 409);
        assertThat(auditSummary("PAPER_EDIT", "legacy-001", "FAILED").toString())
                .isEqualTo(json.createObjectNode().put("code", "PAPER_CHANGED").toString());
    }

    private String upload(String title, String doi) throws Exception {
        var request = multipart("/api/papers").file(pdf()).param("title", title)
                .param("sourceId", "S123").param("year", "2024").param("authors", "Alice;Bob").param("doi", doi);
        var created = write(request, null, 201);
        assertThat(created.path("hasOriginal").asBoolean()).isTrue();
        return created.path("paperId").asText();
    }

    private void uploadBatch() throws Exception {
        byte[] zip = WorkspaceFixture.zip("openalex/csv/import.csv", """
                source_id,year,paper_title,authors,file_path
                S123,2021,Batch title,Alice,paper.pdf
                S123,2021,Missing file,Alice,missing.pdf
                S123,2024,Legacy paper,Alice;Bob,paper.pdf
                """, "paper.pdf", PDF);
        var job = write(multipart("/api/imports").file(new MockMultipartFile("file", "sample.zip", "application/zip", zip)), null, 201);
        assertThat(job.path("ready").asInt()).isEqualTo(1);
        assertThat(job.path("failed").asInt()).isEqualTo(1);
        assertThat(job.path("skipped").asInt()).isEqualTo(1);
        String id = job.path("jobId").asText();
        write(post("/api/imports/" + id + "/confirm"), null, 202);
        await(id, "PARTIAL");
    }

    private void parseAndPublish(String paper) throws Exception {
        assertThat(paper).isEqualTo("38f763c526f4293f8d6ee2f253e9f5d9fb70f809720d7cd077b5656843a0ebd3");
        String parse = start("PARSE", paper);
        await(parse, "FAILED");
        String retry = write(post("/api/jobs/" + parse + "/retry"), null, 202).path("jobId").asText();
        await(retry, "SUCCEEDED");
        mvc.perform(get("/api/papers/" + paper).session(session)).andExpect(jsonPath("$.status").value("READY"));
        String publish = start("PUBLISH", paper);
        await(publish, "SUCCEEDED");
        assertThat(auditSummary("PAPER_PUBLISH", paper, "SUCCEEDED").path("jobId").asText()).isEqualTo(publish);
        assertThat(auditSummary("JOB_EXECUTE", publish, "SUCCEEDED").toString()).doesNotContain("Parsed body");
        mvc.perform(get("/api/papers/" + paper + "/blocks").session(session))
                .andExpect(jsonPath("$.items[0].text").value("Parsed body"))
                .andExpect(jsonPath("$.items[1].type").value("title")).andExpect(jsonPath("$.items[2].page").value(3))
                .andExpect(jsonPath("$.items[2].text").value("Table caption")).andExpect(jsonPath("$.items[2].hasAsset").value(true));
        assertThat(fixture.mineruSubmissions()).isEqualTo(1);
        assertThat(fixture.mineruUploads()).isEqualTo(1);
    }

    private void importAndMatch(String paper) throws Exception {
        mvc.perform(get("/api/sources").param("q", "S123").session(session))
                .andExpect(jsonPath("$[0].sourceId").value("S123"));
        var job = write(post("/api/jobs"), Map.of("kind", "METADATA", "sourceId", "S123", "yearFrom", 2024), 202);
        await(job.path("jobId").asText(), "SUCCEEDED");
        await(start("MATCH", paper), "SUCCEEDED");
        assertThat(getPaper(paper).path("matchedWorkId").asText()).isEqualTo("W123");
        String ambiguous = upload("Ambiguous Study", "10.1/amb");
        String id = start("MATCH", ambiguous);
        await(id, "SUCCEEDED");
        mvc.perform(get("/api/jobs/" + id + "/items").session(session))
                .andExpect(jsonPath("$.items[0].result.candidates.length()").value(2));
        var choice = Map.of("workId", "W124", "version", getPaper(ambiguous).path("version").asLong());
        assertThat(write(post("/api/papers/" + ambiguous + "/match"), choice, 200).path("matchedWorkId").asText()).isEqualTo("W124");
        assertThat(auditSummary("PAPER_MATCH_CONFIRM", ambiguous, "SUCCEEDED").path("workId").asText()).isEqualTo("W124");
    }

    private void repeatPublishWithoutAnotherMutationAudit(String paper) throws Exception {
        String repeat = start("PUBLISH", paper);
        await(repeat, "SUCCEEDED");
        var executed = auditSummary("JOB_EXECUTE", repeat, "SUCCEEDED");
        assertThat(executed.path("itemState").asText()).isEqualTo("SKIPPED");
        assertThat(executed.path("code").asText()).isEqualTo("ALREADY_PUBLISHED");
        mvc.perform(get("/api/audit-events").session(session).param("action", "PAPER_PUBLISH")
                        .param("objectId", paper).param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    private void updateOriginal(String paper) throws Exception {
        String path = "/api/papers/" + paper;
        long version = getPaper(paper).path("version").asLong();
        var replaced = write(multipart(org.springframework.http.HttpMethod.PUT, path + "/file").file(pdf())
                .param("version", String.valueOf(version)), null, 200);
        assertThat(replaced.path("paperId").asText()).isEqualTo(paper);
        assertThat(replaced.path("status").asText()).isEqualTo("UNPROCESSED");
        assertThat(auditSummary("PAPER_REPLACE", paper, "SUCCEEDED").path("revision").asLong()).isEqualTo(2);
        mvc.perform(get(path + "/blocks").session(session)).andExpect(jsonPath("$.total").value(0));
    }

    private void archiveAndRestore(String paper) throws Exception {
        String path = "/api/papers/" + paper + "/archive";
        var archived = write(post(path).param("version", getPaper(paper).path("version").asText()), null, 200);
        assertThat(archived.path("archived").asBoolean()).isTrue();
        var restored = write(delete(path).param("version", archived.path("version").asText()), null, 200);
        assertThat(restored.path("archived").asBoolean()).isFalse();
        assertThat(auditSummary("PAPER_ARCHIVE", paper, "SUCCEEDED").path("version")).isEqualTo(archived.path("version"));
        assertThat(auditSummary("PAPER_RESTORE", paper, "SUCCEEDED").path("version")).isEqualTo(restored.path("version"));
    }

    private String start(String kind, String paper) throws Exception {
        return write(post("/api/jobs"), Map.of("kind", kind, "paperIds", java.util.List.of(paper)), 202)
                .path("jobId").asText();
    }

    private void await(String jobId, String state) throws Exception {
        JsonNode job = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            job = body(mvc.perform(get("/api/jobs/" + jobId).session(session)).andExpect(status().isOk()).andReturn());
            if (job.path("state").asText().equals(state)) return;
            Thread.sleep(50);
        }
        var items = mvc.perform(get("/api/jobs/" + jobId + "/items").session(session)).andReturn();
        assertThat(job.path("state").asText()).withFailMessage("Unexpected job: %s, items: %s", job, body(items)).isEqualTo(state);
    }

    private JsonNode auditSummary(String action, String id, String outcome) throws Exception {
        return new WorkspaceHttp(mvc, json).auditSummary(session, id, action, outcome, "integration");
    }

    private JsonNode getPaper(String id) throws Exception {
        return body(mvc.perform(get("/api/papers/" + id).session(session)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode write(MockHttpServletRequestBuilder request, Object data, int code) throws Exception {
        JsonNode token = body(mvc.perform(get("/api/csrf").session(session)).andReturn());
        request.session(session).header(token.path("headerName").asText(), token.path("token").asText());
        if (data != null) request.contentType("application/json").content(json.writeValueAsBytes(data));
        return body(mvc.perform(request).andExpect(status().is(code)).andReturn());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "input.pdf", "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII));
    }
}
