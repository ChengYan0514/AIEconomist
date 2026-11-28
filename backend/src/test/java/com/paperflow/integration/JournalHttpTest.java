package com.paperflow.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.main.Paperflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class JournalHttpTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @Test
    void aggregatesLegacyAndCurrentPapersByJournalAndReturnsYearlyStats() throws Exception {
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, publication_year, title, authors,
                                      file_revision, original_type, archived_at)
                VALUES (repeat('1', 64), false, 'S124', 2022, 'Urban Growth A', 'Alice', 1, 'PDF', NULL),
                       (repeat('2', 64), false, 'S124', 2023, 'Urban Growth B', 'Bob', 1, 'PDF', NULL),
                       (repeat('3', 64), false, 'S124', 2023, 'Archived Urban', 'Carol', 1, 'PDF', now())
                """);
        jdbc.update("""
                INSERT INTO work (work_id, publication_year, title, doi)
                VALUES ('W901', 2022, 'Urban Growth A', NULL),
                       ('W902', 2023, 'Urban Growth B', NULL)
                """);
        jdbc.update("""
                INSERT INTO work_source (work_id, source_id)
                VALUES ('W901', 'S124'),
                       ('W902', 'S124')
                """);

        var admin = new WorkspaceHttp(mvc, json).login("integration", "workspace-test-password");

        mvc.perform(get("/api/journals").session(admin).param("sort", "PAPERS_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.summary.totalJournals").value(2))
                .andExpect(jsonPath("$.summary.totalPapers").value(3))
                .andExpect(jsonPath("$.summary.publishedPapers").value(1))
                .andExpect(jsonPath("$.summary.causalExtractedPapers").value(1))
                .andExpect(jsonPath("$.summary.causalClaims").value(2))
                .andExpect(jsonPath("$.items[0].sourceId").value("S124"))
                .andExpect(jsonPath("$.items[0].name").value("Journal of Urban Economics"))
                .andExpect(jsonPath("$.items[0].totalPapers").value(2))
                .andExpect(jsonPath("$.items[0].archivedPapers").value(1))
                .andExpect(jsonPath("$.items[0].unprocessedCount").value(2))
                .andExpect(jsonPath("$.items[0].causalExtractedCount").value(0))
                .andExpect(jsonPath("$.items[1].sourceId").value("S123"))
                .andExpect(jsonPath("$.items[1].publishedCount").value(1))
                .andExpect(jsonPath("$.items[1].matchedCount").value(1))
                .andExpect(jsonPath("$.items[1].causalExtractedCount").value(1))
                .andExpect(jsonPath("$.items[1].causalClaimCount").value(2));

        mvc.perform(get("/api/journals").session(admin).param("sort", "CAUSAL_RATE_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceId").value("S123"));

        mvc.perform(get("/api/papers").session(admin).param("causalStatus", "EXTRACTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].paperId").value("legacy-001"))
                .andExpect(jsonPath("$.items[0].causalStatus").value("EXTRACTED"))
                .andExpect(jsonPath("$.items[0].causalClaimCount").value(2));

        mvc.perform(get("/api/papers").session(admin).param("causalStatus", "PREREQUISITE_MISSING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/journals").session(admin).param("filter", "ALL_PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("S123"));

        mvc.perform(get("/api/journals/s124").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journal.sourceId").value("S124"))
                .andExpect(jsonPath("$.importedWorksCount").value(2))
                .andExpect(jsonPath("$.years.length()").value(2))
                .andExpect(jsonPath("$.years[0].year").value(2022))
                .andExpect(jsonPath("$.years[0].total").value(1))
                .andExpect(jsonPath("$.years[1].year").value(2023))
                .andExpect(jsonPath("$.years[1].total").value(1));

        mvc.perform(get("/api/journals/S999999").session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNAL_NOT_FOUND"));

        mvc.perform(get("/api/papers/" + "1".repeat(64)).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName").value("Journal of Urban Economics"));

        seedCausalStatusPapers();
        assertCausalFilter(admin, "EXTRACTING", "4".repeat(64));
        assertCausalFilter(admin, "PENDING_REVIEW", "5".repeat(64));
        assertCausalFilter(admin, "FAILED", "6".repeat(64));
        assertCausalFilter(admin, "EXTRACTED_NO_CAUSAL", "7".repeat(64));
        assertCausalFilter(admin, "NOT_EXTRACTED", "8".repeat(64));

        var export = mvc.perform(get("/api/papers/export").session(admin).param("causalStatus", "EXTRACTED"))
                .andExpect(request().asyncStarted()).andReturn();
        var csv = mvc.perform(asyncDispatch(export)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8).lines()
                .skip(1).map(row -> row.split(",", 2)[0])).containsExactly("legacy-001");

        mvc.perform(get("/api/causal/overview-stats").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededPapers").value(2))
                .andExpect(jsonPath("$.activePapers").value(2))
                .andExpect(jsonPath("$.failedPapers").value(1))
                .andExpect(jsonPath("$.unextractedPapers").value(3))
                .andExpect(jsonPath("$.totalClaims").value(2));

        mvc.perform(get("/api/causal/overview-stats").session(admin).param("sourceId", "S123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededPapers").value(1))
                .andExpect(jsonPath("$.totalClaims").value(2));
    }

    private void seedCausalStatusPapers() {
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO v2_causal_batch (batch_id, ingestion_mode) VALUES (?, 'RAW')", batchId);
        seedPublishedMatchedPaper("4".repeat(64), "W904");
        seedPublishedMatchedPaper("5".repeat(64), "W905");
        seedPublishedMatchedPaper("6".repeat(64), "W906");
        seedPublishedMatchedPaper("7".repeat(64), "W907");
        seedPublishedMatchedPaper("8".repeat(64), "W908");
        insertCausalRun(batchId, "4".repeat(64), "W904", "RUNNING");
        insertCausalRun(batchId, "5".repeat(64), "W905", "PENDING_REVIEW");
        insertCausalRun(batchId, "6".repeat(64), "W906", "FAILED");
        insertCausalRun(batchId, "7".repeat(64), "W907", "SUCCEEDED");
    }

    private void seedPublishedMatchedPaper(String paperId, String workId) {
        UUID parseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, publication_year, title, authors,
                                      file_revision, original_type, matched_work_id)
                VALUES (?, false, 'S124', 2024, ?, 'Tester', 1, 'PDF', ?)
                """, paperId, "Paper " + workId, workId);
        jdbc.update("""
                INSERT INTO v2_parse_artifact (parse_id, paper_id, file_revision, legacy, storage_path, content_path)
                VALUES (?, ?, 1, false, 'v2/p', 'v2/p/content.json')
                """, parseId, paperId);
        jdbc.update("UPDATE v2_paper SET latest_parse_id = ?, published_parse_id = ? WHERE paper_id = ?",
                parseId, parseId, paperId);
    }

    private void insertCausalRun(UUID batchId, String paperId, String workId, String status) {
        jdbc.update("""
                INSERT INTO v2_causal_run (run_id, batch_id, paper_id, work_id, source_id, status)
                VALUES (?, ?, ?, ?, 'S124', ?)
                """, UUID.randomUUID(), batchId, paperId, workId, status);
    }

    private void assertCausalFilter(org.springframework.mock.web.MockHttpSession admin,
                                    String causalStatus, String expectedPaperId) throws Exception {
        mvc.perform(get("/api/papers").session(admin).param("causalStatus", causalStatus))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].paperId").value(expectedPaperId))
                .andExpect(jsonPath("$.items[0].causalStatus").value(causalStatus));
    }
}
