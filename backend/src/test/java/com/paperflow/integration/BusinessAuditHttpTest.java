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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static com.paperflow.integration.WorkspaceFixture.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class BusinessAuditHttpTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private WorkspaceHttp http;
    private MockHttpSession admin;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @AfterAll static void stop() {
        if (fixture != null) fixture.close();
    }

    @BeforeEach void login() throws Exception {
        http = new WorkspaceHttp(mvc, json);
        admin = http.login("integration", "workspace-test-password");
    }

    @Test
    void importKeepsTheUploaderAsActorWhenAnotherAdministratorConfirms() throws Exception {
        var confirmer = http.activate(admin, "audit.confirmer", "导入确认员", "SYSTEM_ADMIN", "audit-confirmer-password");
        byte[] zip = WorkspaceFixture.zip("openalex/csv/import.csv", """
                source_id,year,paper_title,authors,file_path
                S123,2021,Private audit title,Alice,private.pdf
                S123,2021,Bad input,Alice,unreadable-private.pdf
                """, "private.pdf", PDF);
        String job = write(multipart("/api/imports").file(new MockMultipartFile("file", "private.zip",
                "application/zip", zip)), admin, null, 201).path("jobId").asText();
        write(post("/api/imports/{id}/confirm", job), confirmer, null, 202);
        await(job, "PARTIAL");
        audit(job, "JOB_PREFLIGHT", "SUCCEEDED", "integration");
        audit(job, "JOB_SUBMIT", "SUCCEEDED", "audit.confirmer");
        JsonNode rejected = audit(job, "JOB_ITEM_CHECK", "FAILED", "integration");
        assertThat(rejected.path("itemSequence").asInt()).isEqualTo(2);
        assertThat(rejected.path("code").asText()).isEqualTo("PREFLIGHT_FAILED");
        JsonNode executed = audit(job, "JOB_EXECUTE", "SUCCEEDED", "integration");
        assertThat(executed.path("itemState").asText()).isEqualTo("SUCCEEDED");
        assertThat(executed.path("itemSequence").asInt()).isEqualTo(1);
        String id = executed.path("paperId").asText();
        assertThat(audit(id, "PAPER_IMPORT", "SUCCEEDED", "integration").path("jobId").asText()).isEqualTo(job);
        assertThat(getPaper(id).path("title").asText()).isEqualTo("Private audit title");
        assertThat(rejected.toString() + executed).doesNotContain("unreadable-private.pdf", "private.zip", "Private audit title",
                "audit-confirmer-password", "workspace-test-password");
    }

    @Test
    void rejectedSuccessAuditRollsBackThePaperEditAndKeepsHistoryReadable() throws Exception {
        var legacy = WorkspaceFixture.legacyContents(jdbc);
        jdbc.execute("""
                ALTER TABLE v2_audit_event ADD CONSTRAINT test_reject_edit_audit
                CHECK (action <> 'PAPER_EDIT' OR outcome <> 'SUCCEEDED')
                """);
        try {
            var edit = json.createObjectNode().put("sourceId", "S123").put("year", 2024)
                    .put("title", "Title that must roll back").put("authors", "Alice;Bob").put("version", 0);
            write(patch("/api/papers/legacy-001"), admin, edit, 500);
            assertThat(getPaper("legacy-001").path("title").asText()).isEqualTo("Legacy paper");
            assertThat(getPaper("legacy-001").path("version").asLong()).isZero();
            assertThat(audit("legacy-001", "PAPER_EDIT", "FAILED", "integration").path("code").asText())
                    .isEqualTo("INTERNAL");
            assertThat(WorkspaceFixture.legacyContents(jdbc)).isEqualTo(legacy);
        } finally {
            jdbc.execute("ALTER TABLE v2_audit_event DROP CONSTRAINT test_reject_edit_audit");
        }
    }

    @Test
    void expiredPublishCannotWriteFullTextOrASuccessfulAuditEvent() throws Exception {
        String id = write(multipart("/api/papers").file(new MockMultipartFile("file", "lease.pdf",
                        "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII)))
                .param("title", "Lease fixture").param("sourceId", "S123").param("year", "2024").param("authors", "Alice"),
                admin, null, 201).path("paperId").asText();
        String parse = start("PARSE", id);
        await(parse, "FAILED");
        assertThat(audit(parse, "JOB_EXECUTE", "FAILED", "integration").path("code").asText()).isEqualTo("PARSE_FAILED");
        String retry = write(post("/api/jobs/{id}/retry", parse), admin, null, 202).path("jobId").asText();
        assertThat(audit(retry, "JOB_RETRY", "SUCCEEDED", "integration").path("retryOf").asText()).isEqualTo(parse);
        await(retry, "SUCCEEDED");
        String publish;
        try (var connection = dataSource.getConnection();
             var lock = connection.prepareStatement("SELECT paper_id FROM v2_paper WHERE paper_id = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            lock.setString(1, id);
            lock.executeQuery().close();
            publish = start("PUBLISH", id);
            await(publish, "RUNNING");
            jdbc.update("UPDATE v2_job_item SET claim_until = clock_timestamp() WHERE job_id = CAST(? AS uuid)", publish);
            connection.commit();
        }
        await(publish, "FAILED");
        assertThat(audit(publish, "JOB_EXECUTE", "FAILED", "integration").path("code").asText()).isEqualTo("CLAIM_EXPIRED");
        assertThat(audit(id, "PAPER_PUBLISH", "FAILED", "integration").path("jobId").asText()).isEqualTo(publish);
        mvc.perform(get("/api/audit-events").session(admin).param("action", "PAPER_PUBLISH")
                        .param("objectId", id).param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/papers/{id}/blocks", id).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        assertThat(getPaper(id).path("status").asText()).isEqualTo("READY");
    }

    private String start(String kind, String id) throws Exception {
        var request = json.createObjectNode().put("kind", kind);
        request.putArray("paperIds").add(id);
        return write(post("/api/jobs"), admin, request, 202).path("jobId").asText();
    }

    private void await(String id, String expected) throws Exception {
        String state = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            state = http.body(mvc.perform(get("/api/jobs/{id}", id).session(admin))
                    .andExpect(status().isOk()).andReturn()).path("state").asText();
            if (state.equals(expected)) return;
            Thread.sleep(50);
        }
        assertThat(state).as("job %s", id).isEqualTo(expected);
    }

    private JsonNode audit(String object, String action, String outcome, String actor) throws Exception {
        return http.auditSummary(admin, object, action, outcome, actor);
    }

    private JsonNode write(MockHttpServletRequestBuilder request, MockHttpSession session, JsonNode body, int status)
            throws Exception {
        return http.body(mvc.perform(http.write(request, session, body)).andExpect(status().is(status)).andReturn());
    }

    private JsonNode getPaper(String id) throws Exception {
        return http.body(mvc.perform(get("/api/papers/{id}", id).session(admin)).andExpect(status().isOk()).andReturn());
    }
}
