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
class JobItemsHttpTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
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
    void filtersBeforeCountingAndPagingAndRetriesEveryFailedItem() throws Exception {
        String archived = upload("Archived before parsing", PDF);
        var paper = http.body(mvc.perform(get("/api/papers/{id}", archived).session(admin))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(http.write(post("/api/papers/{id}/archive", archived).param("version", paper.path("version").asText()),
                        admin, null))
                .andExpect(status().isOk());
        String interrupted = upload("Interrupted parsing", PDF);
        String succeeded = upload("Successful parsing", PDF);
        String rejected = upload("Rejected by MinerU", PDF + "\n");
        String id = startParse(archived, interrupted, succeeded, rejected);

        JsonNode summary = await(id, "PARTIAL");
        assertPage(id, "", 0, 4, archived, "SKIPPED");
        assertPage(id, "FAILED", 0, 2, interrupted, "FAILED");
        assertPage(id, "FAILED", 1, 2, rejected, "FAILED");
        mvc.perform(get("/api/jobs/{id}/items", id).session(admin).param("state", "FAILED")
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items").isEmpty());
        assertPage(id, "SUCCEEDED", 0, 1, succeeded, "SUCCEEDED");
        for (String state : new String[]{"PARTIAL", "RUNNING"}) {
            mvc.perform(get("/api/jobs/{id}/items", id).session(admin).param("state", state))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.items").isEmpty());
        }
        assertThat(summary.path("canRetry").asBoolean()).isTrue();
        assertThat(summary.path("total").asInt()).isEqualTo(4);
        assertThat(summary.path("succeeded").asInt()).isEqualTo(1);
        assertThat(summary.path("skipped").asInt()).isEqualTo(1);
        assertThat(summary.path("failed").asInt()).isEqualTo(2);
        retryAndCheckScope(id, interrupted, rejected);
        assertThat(job(id)).isEqualTo(summary);
    }

    @Test
    void marksZipPreflightFailuresAndRequiresCorrectedInput() throws Exception {
        byte[] zip = WorkspaceFixture.zip("import.csv", """
                source_id,year,paper_title,authors,file_path
                S123,2025,Ready to import,Alice,paper.pdf
                S123,2025,Fix missing file,Alice,missing.pdf
                """, "paper.pdf", PDF);
        var upload = multipart("/api/imports").file(new MockMultipartFile("file", "preflight.zip", "application/zip", zip));
        String id = http.body(mvc.perform(http.write(upload, admin, null)).andExpect(status().isCreated())
                .andReturn()).path("jobId").asText();
        mvc.perform(http.write(post("/api/imports/{id}/confirm", id), admin, null)).andExpect(status().isAccepted());
        var finished = await(id, "PARTIAL");
        assertThat(finished.path("failed").asInt()).isEqualTo(1);
        mvc.perform(get("/api/jobs/{id}/items", id).session(admin).param("state", "FAILED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].target").value("Fix missing file"))
                .andExpect(jsonPath("$.items[0].inputError").value(true));
        assertThat(finished.required("canRetry").asBoolean()).isFalse();
        mvc.perform(http.write(post("/api/jobs/{id}/retry", id), admin, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("预检失败项需要修正后重新上传"));
    }

    private void retryAndCheckScope(String id, String interrupted, String rejected) throws Exception {
        var reader = http.activate(admin, "job.reader", "任务只读用户", "READ_ONLY", "reader-test-password");
        mvc.perform(get("/api/jobs/{id}/items", id).session(reader).param("state", "FAILED"))
                .andExpect(status().isForbidden());
        mvc.perform(http.write(post("/api/jobs/{id}/retry", id), reader, null))
                .andExpect(status().isForbidden());

        var result = mvc.perform(http.write(post("/api/jobs/{id}/retry", id), admin, null))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.retryOf").value(id))
                .andExpect(jsonPath("$.total").value(2)).andReturn();
        String retry = http.body(result).path("jobId").asText();
        await(retry, "PARTIAL");
        assertPage(retry, "", 0, 2, interrupted, "SUCCEEDED");
        assertPage(retry, "", 1, 2, rejected, "FAILED");
    }

    private void assertPage(String id, String state, int page, int total, String paperId, String itemState) throws Exception {
        mvc.perform(get("/api/jobs/{id}/items", id).session(admin).param("state", state)
                        .param("page", String.valueOf(page)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].paperId").value(paperId))
                .andExpect(jsonPath("$.items[0].state").value(itemState))
                .andExpect(jsonPath("$.items[0].inputError").value(false));
    }

    private String upload(String title, String pdf) throws Exception {
        var file = new MockMultipartFile("file", "input.pdf", "application/pdf", pdf.getBytes(StandardCharsets.US_ASCII));
        var result = mvc.perform(http.write(multipart("/api/papers").file(file).param("title", title)
                        .param("sourceId", "S123").param("year", "2024").param("authors", "Alice"), admin, null))
                .andExpect(status().isCreated()).andReturn();
        return http.body(result).path("paperId").asText();
    }

    private String startParse(String... ids) throws Exception {
        var request = json.createObjectNode().put("kind", "PARSE");
        var papers = request.putArray("paperIds");
        for (String id : ids) papers.add(id);
        return http.body(mvc.perform(http.write(post("/api/jobs"), admin, request)).andExpect(status().isAccepted())
                .andReturn()).path("jobId").asText();
    }

    private JsonNode await(String id, String expectedState) throws Exception {
        JsonNode result = job(id);
        for (int attempt = 0; attempt < 100 && !result.path("state").asText().equals(expectedState); attempt++) {
            Thread.sleep(50);
            result = job(id);
        }
        assertThat(result.path("state").asText()).as("Task summary: %s", result).isEqualTo(expectedState);
        return result;
    }

    private JsonNode job(String id) throws Exception {
        return http.body(mvc.perform(get("/api/jobs/{id}", id).session(admin)).andExpect(status().isOk()).andReturn());
    }
}
