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

import static com.paperflow.integration.WorkspaceFixture.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class PaperJobHistoryHttpTest {
    private static final String HISTORY = "/api/papers/{paperId}/jobs";
    private static final String LEGACY_DUPLICATE = "legacy-history-duplicate";
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
    void listsThePapersOwnTaskItemResultsNewestFirstAndPagesAfterFiltering() throws Exception {
        String legacy = "legacy-001";
        mvc.perform(get(HISTORY, legacy).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());

        String other = upload("Unrelated history paper");
        JsonNode first = startPublish(other, legacy);
        JsonNode second = startPublish(legacy);
        assertPage(legacy, 0, 2, second, 1, "已有发布全文");
        assertPage(legacy, 1, 2, first, 2, "已有发布全文");
        assertPage(other, 0, 1, first, 1, "请先生成解析产物");
        mvc.perform(get(HISTORY, legacy).session(admin).param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void includesRegisteredImportResultsAndExcludesPreflightOnlyIds() throws Exception {
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, publication_year, title, authors, file_revision, legacy_status)
                VALUES (?, true, 'S123', 2024, 'Other title before preview', 'Alice;Bob', 0, 'UNPROCESSED')
                """, LEGACY_DUPLICATE);
        String knownId = upload("Known duplicate at preflight");
        String jobId = preflight();
        var jobItems = mvc.perform(get("/api/jobs/{id}/items", jobId).session(admin)).andExpect(status().isOk()).andReturn();
        var rows = http.body(jobItems).path("items");
        String registeredElsewhere = rows.get(0).path("paperId").asText();
        String imported = rows.get(1).path("paperId").asText();
        String provisionalLegacyId = rows.get(3).path("paperId").asText();
        mvc.perform(get(HISTORY, registeredElsewhere).session(admin)).andExpect(status().isNotFound());
        assertThat(upload("Registered after preview")).isEqualTo(registeredElsewhere);
        makeLegacyDuplicateAfterPreflight();
        assertThat(provisionalLegacyId).isNotEqualTo(LEGACY_DUPLICATE);
        assertEmpty(registeredElsewhere);
        assertEmpty(LEGACY_DUPLICATE);
        assertImport(knownId, jobId, "SKIPPED", "论文已存在");

        mvc.perform(http.write(post("/api/imports/{id}/confirm", jobId), admin, null)).andExpect(status().isAccepted());
        awaitImport(jobId);
        assertImport(imported, jobId, "SUCCEEDED", "原件已登记");
        assertImport(registeredElsewhere, jobId, "SKIPPED", "论文已存在");
        assertImport(LEGACY_DUPLICATE, jobId, "SKIPPED", "论文已存在");
        mvc.perform(get("/api/jobs/{id}/items", jobId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[3].paperId").value(LEGACY_DUPLICATE))
                .andExpect(jsonPath("$.items[3].result.paperId").value(LEGACY_DUPLICATE))
                .andExpect(jsonPath("$.items[3].preflight.status").value("IMPORTABLE"));
        assertLateDuplicateAudit(jobId, registeredElsewhere);
    }

    private void assertLateDuplicateAudit(String jobId, String registeredElsewhere) throws Exception {
        var executions = mvc.perform(get("/api/audit-events").session(admin)
                        .param("action", "JOB_EXECUTE").param("objectId", jobId).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andReturn();
        long latest = http.body(executions).path("items").get(0).path("eventId").asLong();
        mvc.perform(get("/api/audit-events/{id}", latest).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.paperId").value(LEGACY_DUPLICATE))
                .andExpect(jsonPath("$.summary.itemState").value("SKIPPED"))
                .andExpect(jsonPath("$.summary.code").value("DUPLICATE_PAPER"));
        mvc.perform(get("/api/audit-events").session(admin)
                        .param("action", "PAPER_IMPORT").param("objectId", registeredElsewhere).param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/audit-events").session(admin)
                        .param("action", "PAPER_IMPORT").param("objectId", LEGACY_DUPLICATE).param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    private void makeLegacyDuplicateAfterPreflight() throws Exception {
        var edit = json.createObjectNode().put("sourceId", "S123").put("year", 2024)
                .put("title", "Made duplicate by a legacy edit").put("authors", "Alice;Bob").put("version", 1);
        mvc.perform(http.write(patch("/api/papers/{id}", LEGACY_DUPLICATE), admin, edit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(LEGACY_DUPLICATE));
    }

    @Test
    void requiresAValidSessionAndBothPermissionsAndValidatesPagination() throws Exception {
        String legacy = "legacy-001";
        mvc.perform(get(HISTORY, legacy)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        var reader = http.activate(admin, "history.reader", "论文只读用户", "READ_ONLY", "history-reader-password");
        mvc.perform(get("/api/papers/{id}", legacy).session(reader)).andExpect(status().isOk());
        mvc.perform(get(HISTORY, legacy).session(reader)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(head(HISTORY, legacy).session(reader)).andExpect(status().isForbidden());
        var maintainer = http.activate(admin, "history.maintainer", "论文维护用户", "DATA_MAINTAINER", "history-maintainer-password");
        mvc.perform(get(HISTORY, legacy).session(maintainer)).andExpect(status().isOk());
        mvc.perform(get(HISTORY, legacy).session(maintainer).param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get(HISTORY, legacy).session(maintainer).param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get(HISTORY, "missing-paper").session(maintainer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    void requiresPaperReadEvenWhenTheUserCanReadTasks() throws Exception {
        var role = json.createObjectNode().put("name", "任务查阅").put("description", "");
        role.putArray("permissions").add("JOB_READ");
        var created = mvc.perform(http.write(post("/api/roles"), admin, role)).andExpect(status().isCreated()).andReturn();
        String roleId = http.body(created).path("id").asText();
        var reviewer = http.activate(admin, "history.jobonly", "任务查阅员", roleId, "history-reviewer-password");
        mvc.perform(get("/api/jobs").session(reviewer)).andExpect(status().isOk());
        mvc.perform(get(HISTORY, "legacy-001").session(reviewer)).andExpect(status().isForbidden());
        mvc.perform(head(HISTORY, "legacy-001").session(reviewer)).andExpect(status().isForbidden());

        role.putArray("permissions").add("JOB_READ").add("PAPER_READ");
        role.put("expectedUserCount", 1);
        mvc.perform(http.write(put("/api/roles/{id}", roleId), admin, role)).andExpect(status().isOk());
        mvc.perform(get(HISTORY, "legacy-001").session(reviewer)).andExpect(status().isOk());
        mvc.perform(head(HISTORY, "legacy-001").session(reviewer)).andExpect(status().isOk());
    }

    private void assertPage(String paperId, int page, int total, JsonNode job, int sequence, String message) throws Exception {
        mvc.perform(get(HISTORY, paperId).session(admin).param("page", String.valueOf(page)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(total)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].jobId").value(job.path("jobId").asText()))
                .andExpect(jsonPath("$.items[0].sequence").value(sequence)).andExpect(jsonPath("$.items[0].kind").value("PUBLISH"))
                .andExpect(jsonPath("$.items[0].state").value("SKIPPED")).andExpect(jsonPath("$.items[0].message").value(message))
                .andExpect(jsonPath("$.items[0].createdAt").value(job.path("createdAt").asText()));
    }

    private String upload(String title) throws Exception {
        var file = new MockMultipartFile("file", "input.pdf", "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII));
        var result = mvc.perform(http.write(multipart("/api/papers").file(file).param("title", title)
                        .param("sourceId", "S123").param("year", "2025").param("authors", "Alice"), admin, null))
                .andExpect(status().isCreated()).andReturn();
        return http.body(result).path("paperId").asText();
    }

    private String preflight() throws Exception {
        byte[] zip = WorkspaceFixture.zip("batch.csv", """
                source_id,year,paper_title,authors,file_path
                S123,2025,Registered after preview,Alice,paper.pdf
                S123,2025,Registered by this batch,Alice,paper.pdf
                S123,2025,Known duplicate at preflight,Alice,paper.pdf
                S123,2024,Made duplicate by a legacy edit,Alice;Bob,paper.pdf
                """, "paper.pdf", PDF);
        var request = multipart("/api/imports").file(new MockMultipartFile("file", "history.zip", "application/zip", zip));
        return http.body(mvc.perform(http.write(request, admin, null)).andExpect(status().isCreated()).andReturn())
                .path("jobId").asText();
    }

    private void awaitImport(String jobId) throws Exception {
        var request = get("/api/jobs/{jobId}", jobId).session(admin);
        for (int attempt = 0; attempt < 100; attempt++) {
            var job = http.body(mvc.perform(request).andExpect(status().isOk()).andReturn());
            if (job.path("state").asText().equals("SUCCEEDED")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("ZIP import did not succeed");
    }

    private void assertEmpty(String paperId) throws Exception {
        mvc.perform(get(HISTORY, paperId).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());
    }

    private void assertImport(String paperId, String jobId, String state, String message) throws Exception {
        mvc.perform(get(HISTORY, paperId).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].jobId").value(jobId))
                .andExpect(jsonPath("$.items[0].kind").value("ORIGINAL_IMPORT")).andExpect(jsonPath("$.items[0].state").value(state))
                .andExpect(jsonPath("$.items[0].message").value(message));
    }

    private JsonNode startPublish(String... ids) throws Exception {
        var request = json.createObjectNode().put("kind", "PUBLISH");
        var papers = request.putArray("paperIds");
        for (String id : ids) papers.add(id);
        return http.body(mvc.perform(http.write(post("/api/jobs"), admin, request))
                .andExpect(status().isAccepted()).andReturn());
    }
}
