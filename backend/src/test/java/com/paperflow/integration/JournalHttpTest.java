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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
                INSERT INTO v2_openalex_work (source_id, work_id, year, title, doi, authors)
                VALUES ('S124', 'W901', 2022, 'Urban Growth A', NULL, 'Alice'),
                       ('S124', 'W902', 2023, 'Urban Growth B', NULL, 'Bob')
                """);

        var admin = new WorkspaceHttp(mvc, json).login("integration", "workspace-test-password");

        mvc.perform(get("/api/journals").session(admin).param("sort", "PAPERS_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.summary.totalJournals").value(2))
                .andExpect(jsonPath("$.summary.totalPapers").value(3))
                .andExpect(jsonPath("$.summary.publishedPapers").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("S124"))
                .andExpect(jsonPath("$.items[0].name").value("Journal of Urban Economics"))
                .andExpect(jsonPath("$.items[0].totalPapers").value(2))
                .andExpect(jsonPath("$.items[0].archivedPapers").value(1))
                .andExpect(jsonPath("$.items[0].unprocessedCount").value(2))
                .andExpect(jsonPath("$.items[1].sourceId").value("S123"))
                .andExpect(jsonPath("$.items[1].publishedCount").value(1))
                .andExpect(jsonPath("$.items[1].matchedCount").value(1));

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
    }
}
