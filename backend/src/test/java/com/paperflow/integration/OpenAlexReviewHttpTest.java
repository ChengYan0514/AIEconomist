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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static com.paperflow.integration.WorkspaceFixture.PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OpenAlexReviewHttpTest {
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
    void keepsCandidateSourceAndBibliographyWhenTheLocalPaperChanges() throws Exception {
        await(start(json.createObjectNode().put("kind", "METADATA").put("sourceId", "S123")));
        var pdf = new MockMultipartFile("file", "review.pdf", "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII));
        var upload = multipart("/api/papers").file(pdf).param("sourceId", "S123").param("year", "2020")
                .param("title", "Local review example").param("authors", "Local Author").param("doi", "10.1/amb");
        var open = http.body(mvc.perform(http.write(upload, admin, null)).andExpect(status().isCreated()).andReturn());
        String paper = open.path("paperId").asText();
        var match = json.createObjectNode().put("kind", "MATCH");
        match.putArray("paperIds").add(paper);
        String job = start(match);
        await(job);

        var edit = json.createObjectNode().put("sourceId", "S124").put("year", 2021).put("title", "New local title")
                .put("authors", "New Author").put("doi", "10.1/amb").put("version", open.path("version").asLong());
        var changed = http.body(mvc.perform(http.write(patch("/api/papers/{id}", paper), admin, edit))
                .andExpect(status().isOk()).andReturn());
        assertThat(changed.path("sourceId").asText()).isEqualTo("S124");
        assertThat(changed.path("title").asText()).isEqualTo("New local title");
        mvc.perform(get("/api/jobs/{id}/items", job).session(admin).param("state", "SKIPPED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].result.candidateSource.sourceId").value("S123"))
                .andExpect(jsonPath("$.items[0].result.candidateSource.name").value("Review of Applied Economics"))
                .andExpect(jsonPath("$.items[0].result.candidates.length()").value(2))
                .andExpect(jsonPath("$.items[0].result.candidates[0].workId").value("W124"))
                .andExpect(jsonPath("$.items[0].result.candidates[0].title").value("Ambiguous Study"))
                .andExpect(jsonPath("$.items[0].result.candidates[0].year").value(2024))
                .andExpect(jsonPath("$.items[0].result.candidates[0].doi").value("10.1/amb"));

        assertRejected(paper, open.path("version").asLong(), "PAPER_CHANGED");
        assertRejected(paper, changed.path("version").asLong(), "CANDIDATE_MISSING");
        mvc.perform(get("/api/papers/{id}", paper).session(admin)).andExpect(jsonPath("$.matchedWorkId").isEmpty());
    }

    private void assertRejected(String paper, long version, String reason) throws Exception {
        var choice = json.createObjectNode().put("workId", "W124").put("version", version);
        mvc.perform(http.write(post("/api/papers/{id}/match", paper), admin, choice))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(reason));
    }

    @Test
    void rejectsAConfirmedCandidateWhenAnImportShadowsItBeforeThePaperCanBeBound() throws Exception {
        jdbc.update("INSERT INTO work VALUES ('W900', 2030, 'Legacy candidate', '10.1/shadow-original')");
        jdbc.update("INSERT INTO work_source VALUES ('W900', 'S123')");
        jdbc.update("INSERT INTO " + fixture.snapshot() + ".works VALUES ('W900', 2030, 'Replacement snapshot title', '10.1/shadow-replaced')");
        jdbc.update("INSERT INTO " + fixture.snapshot() + ".works_locations VALUES ('W900', 'S123')");
        var pdf = new MockMultipartFile("file", "shadow.pdf", "application/pdf", PDF.getBytes(StandardCharsets.US_ASCII));
        var upload = multipart("/api/papers").file(pdf).param("sourceId", "S123").param("year", "2030")
                .param("title", "Shadowed candidate").param("authors", "Local Author").param("doi", "10.1/shadow-original");
        var open = http.body(mvc.perform(http.write(upload, admin, null)).andExpect(status().isCreated()).andReturn());
        String paper = open.path("paperId").asText();
        var choice = json.createObjectNode().put("workId", "W900").put("version", open.path("version").asLong());
        var confirm = http.write(post("/api/papers/{id}/match", paper), admin, choice);
        var response = new FutureTask<>(() -> mvc.perform(confirm).andReturn());
        try (var connection = jdbc.getDataSource().getConnection();
             var lock = connection.prepareStatement("SELECT paper_id FROM v2_paper WHERE paper_id = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            lock.setString(1, paper);
            lock.executeQuery().close();
            new Thread(response, "test-candidate-confirmation").start();
            awaitBlockedPaperLock();
            await(start(json.createObjectNode().put("kind", "METADATA").put("sourceId", "S123").put("yearFrom", 2030)));
            connection.commit();
        }
        var result = response.get(10, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(http.body(result).path("code").asText()).isEqualTo("CANDIDATE_MISSING");
        mvc.perform(get("/api/papers/{id}", paper).session(admin)).andExpect(jsonPath("$.matchedWorkId").isEmpty());
    }

    private void awaitBlockedPaperLock() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                                   AND query LIKE '%SELECT 1 FROM v2_paper WHERE paper_id = % FOR UPDATE%')
                    """, Boolean.class))) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Confirmation did not reach the paper lock");
    }

    private String start(JsonNode input) throws Exception {
        return http.body(mvc.perform(http.write(post("/api/jobs"), admin, input))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
    }

    private void await(String id) throws Exception {
        JsonNode job = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            job = http.body(mvc.perform(get("/api/jobs/{id}", id).session(admin))
                    .andExpect(status().isOk()).andReturn());
            if (job.path("state").asText().equals("SUCCEEDED")) return;
            Thread.sleep(50);
        }
        assertThat(job).withFailMessage("Expected the job to complete successfully").isNotNull();
        assertThat(job.path("state").asText()).isEqualTo("SUCCEEDED");
    }
}
