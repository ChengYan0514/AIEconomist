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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OverviewHttpTest {
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
    void countCoversAllActiveHistoricalAndCurrentPapersThatMatchTheCsvFilter() throws Exception {
        seedHistoricalPapers();
        seedCurrentPapers();
        var admin = new WorkspaceHttp(mvc, json).login("integration", "workspace-test-password");
        assertCountAndExport(admin, "UNPROCESSED", "legacy-unprocessed", "a".repeat(64));
        assertCountAndExport(admin, "READY", "legacy-ready", "b".repeat(64));
        assertCountAndExport(admin, "FAILED", "legacy-failed", "c".repeat(64));
        assertCountAndExport(admin, "PUBLISHED", "legacy-001", "d".repeat(64));
        assertArchived(admin, "UNPROCESSED", "e".repeat(64));
        assertArchived(admin, "PUBLISHED", "legacy-archived");
    }

    private void assertCountAndExport(MockHttpSession admin, String paperStatus, String legacyId, String currentId)
            throws Exception {
        mvc.perform(get("/api/papers").session(admin)
                        .param("status", paperStatus).param("archived", "false").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value(paperStatus));
        var export = mvc.perform(get("/api/papers/export").session(admin)
                        .param("status", paperStatus).param("archived", "false"))
                .andExpect(request().asyncStarted()).andReturn();
        var csv = mvc.perform(asyncDispatch(export)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8).lines()
                .skip(1).map(row -> row.split(",", 2)[0])).containsExactlyInAnyOrder(legacyId, currentId);
    }

    private void assertArchived(MockHttpSession admin, String paperStatus, String id) throws Exception {
        mvc.perform(get("/api/papers").session(admin).param("status", paperStatus).param("archived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].paperId").value(id));
    }

    private void seedHistoricalPapers() {
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, file_revision, original_type, archived_at, legacy_status) VALUES
                    ('legacy-unprocessed', true, 0, 'PDF', NULL, 'UNPROCESSED'),
                    ('legacy-ready', true, 0, 'PDF', NULL, 'READY'),
                    ('legacy-failed', true, 0, 'PDF', NULL, 'FAILED'),
                    ('legacy-unsupported', true, 0, 'XML', NULL, 'UNSUPPORTED'),
                    ('legacy-archived', true, 0, 'PDF', now(), 'PUBLISHED')
                """);
    }

    private void seedCurrentPapers() {
        jdbc.update("""
                INSERT INTO v2_paper (paper_id, legacy, source_id, publication_year, title, authors,
                                      file_revision, original_type, archived_at)
                SELECT repeat(id, 64), false, 'S123', 2024, 'Paper ' || id, 'Alice', 1, 'PDF',
                    CASE WHEN id = 'e' THEN now() END
                FROM (VALUES ('a'), ('b'), ('c'), ('d'), ('e')) AS papers(id)
                """);
        var ready = UUID.randomUUID();
        var published = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO v2_parse_artifact (parse_id, paper_id, file_revision, legacy, storage_path, content_path)
                VALUES (?, repeat('b', 64), 1, false, 'parsed/b', 'parsed/b/content.json'),
                       (?, repeat('d', 64), 1, false, 'parsed/d', 'parsed/d/content.json')
                """, ready, published);
        jdbc.update("UPDATE v2_paper SET latest_parse_id = ? WHERE paper_id = repeat('b', 64)", ready);
        jdbc.update("UPDATE v2_paper SET latest_parse_id = ?, published_parse_id = ? WHERE paper_id = repeat('d', 64)",
                published, published);
        var task = UUID.randomUUID();
        jdbc.update("INSERT INTO v2_job (job_id, kind, username) VALUES (?, 'PARSE', 'integration')", task);
        jdbc.update("""
                INSERT INTO v2_job_item (job_id, kind, sequence, target, paper_id, file_revision, state) VALUES
                    (?, 'PARSE', 1, 'Old revision', repeat('a', 64), 0, 'FAILED'),
                    (?, 'PARSE', 2, 'Current revision', repeat('c', 64), 1, 'FAILED'),
                    (?, 'PARSE', 3, 'Published revision', repeat('d', 64), 1, 'FAILED')
                """, task, task, task);
    }
}
