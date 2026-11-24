package com.paperflow.integration;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class CausalGraphHttpTest {
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
    void servesSummaryFilteredGraphFieldsVariableRelationAndPaperClaimsWithReadModelPersistence() throws Exception {
        mvc.perform(http.write(post("/api/causal/read-model/refresh"), admin, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.indexedEdges").value(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_causal_edge_index", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_causal_snapshot", Integer.class)).isEqualTo(3);

        mvc.perform(get("/api/causal/summary").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.totalClaimRecords").value(2))
                .andExpect(jsonPath("$.overview.totalStandardClaims").value(2))
                .andExpect(jsonPath("$.subfields[0]").value("Public Economics"));

        mvc.perform(get("/api/causal/graph").session(admin)
                        .param("minRecordCount", "1").param("minDiversity", "1").param("subfields", "Public Economics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.nodes.length()").value(3));

        mvc.perform(get("/api/causal/fields").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].subfield").value("Public Economics"))
                .andExpect(jsonPath("$.items[0].topic").value("Fiscal Multiplier"))
                .andExpect(jsonPath("$.insights.topRelations.length()").value(2));

        mvc.perform(get("/api/causal/variables/{name}", "Fiscal Policy").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.node.asCauseCount").value(2))
                .andExpect(jsonPath("$.subfieldCounts['Public Economics']").value(2))
                .andExpect(jsonPath("$.yearCounts['2024']").value(2));

        mvc.perform(get("/api/causal/relations").session(admin)
                        .param("cause", "Fiscal Policy").param("effect", "Inflation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edge.dominantSignCategory").value("null"))
                .andExpect(jsonPath("$.claims[0].signCategory").value("null"))
                .andExpect(jsonPath("$.claims[0].paperId").value("legacy-001"));

        mvc.perform(get("/api/causal/papers/{workId}", "W009").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paper.paperId").value("legacy-001"))
                .andExpect(jsonPath("$.claims.length()").value(2));

        mvc.perform(get("/api/causal/papers/{workId}/summary", "W009").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimRecordCount").value(2))
                .andExpect(jsonPath("$.hasClaims").value(true));

        mvc.perform(get("/api/causal/search/terms").session(admin).param("q", "fis"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("Fiscal Policy"));
        mvc.perform(get("/api/causal/search/variables").session(admin).param("q", "growth"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].variable").value("Economic Growth"));
        mvc.perform(get("/api/causal/search/papers").session(admin).param("q", "Legacy"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].workId").value("W009"));
    }

    @Test
    void openAlexMetadataImportSyncsV2WorkTopicsForFieldAnalysis() throws Exception {
        var job = http.body(mvc.perform(http.write(post("/api/jobs"), admin,
                json.createObjectNode().put("kind", "METADATA").put("sourceId", "S123").put("yearFrom", 2024)))
                .andExpect(status().isAccepted()).andReturn());
        String jobId = job.path("jobId").asText();
        for (int i = 0; i < 60; i++) {
            var state = http.body(mvc.perform(get("/api/jobs/{id}", jobId).session(admin)).andReturn()).path("state").asText();
            if ("SUCCEEDED".equals(state)) break;
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_openalex_work_topic WHERE work_id = 'W123'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void readOnlyUserCanReadGraphButCannotExtractOrRefreshWithoutPermission() throws Exception {
        var reader = http.activate(admin, "causal.reader", "只读研究", "READ_ONLY", "causal-reader-password");
        mvc.perform(get("/api/causal/summary").session(reader)).andExpect(status().isOk());
        mvc.perform(http.write(post("/api/causal/read-model/refresh"), reader, null)).andExpect(status().isForbidden());
        mvc.perform(http.write(post("/api/causal/extractions/estimate"), reader,
                json.createObjectNode())).andExpect(status().isForbidden());
    }
}
