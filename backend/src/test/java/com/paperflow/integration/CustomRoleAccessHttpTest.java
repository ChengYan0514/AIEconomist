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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class CustomRoleAccessHttpTest {
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private WorkspaceHttp http;
    private MockHttpSession admin;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) { fixture = WorkspaceFixture.configure(properties); }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @BeforeEach void login() throws Exception {
        http = new WorkspaceHttp(mvc, json);
        admin = http.login("integration", "workspace-test-password");
    }

    @Test
    void canReadAndRetryFailedParsingButCannotRetryContentImport() throws Exception {
        var parser = member("自定义文件解析", "roles.parser", "JOB_READ", "PDF_PARSE");
        var parse = failedJob("PARSE");
        var publish = failedJob("PUBLISH");
        mvc.perform(get("/api/papers").session(parser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/sources").session(parser).param("q", "S123")).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/{id}/items", publish).session(parser)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("FAILED"));
        mvc.perform(http.write(post("/api/jobs/{id}/retry", publish), parser, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(http.write(post("/api/jobs/{id}/retry", parse), parser, null))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.retryOf").value(parse.toString()))
                .andExpect(jsonPath("$.username").value("roles.parser"));
    }

    @Test
    void independentlyGrantsCsvWithoutReadOrAdministrativePermissions() throws Exception {
        var exporter = member("仅导出 CSV", "roles.export", "PAPER_CSV_EXPORT");
        mvc.perform(get("/api/papers").session(exporter)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").session(exporter)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-events").session(exporter)).andExpect(status().isForbidden());
        mvc.perform(http.write(delete("/api/roles/READ_ONLY"), exporter, null)).andExpect(status().isForbidden());
        var csv = mvc.perform(get("/api/papers/export").session(exporter)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(csv)).andExpect(status().isOk());
    }

    private MockHttpSession member(String name, String username, String... permissions) throws Exception {
        var role = json.createObjectNode().put("name", name).put("description", "");
        var codes = role.putArray("permissions");
        for (String code : permissions) codes.add(code);
        var created = mvc.perform(http.write(post("/api/roles"), admin, role)).andExpect(status().isCreated()).andReturn();
        return http.activate(admin, username, name, http.body(created).path("id").asText(), "custom-role-personal-password");
    }

    private UUID failedJob(String kind) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO v2_job (job_id, kind, username) VALUES (?, ?, 'integration')", id, kind);
        jdbc.update("""
                INSERT INTO v2_job_item (job_id, kind, sequence, target, paper_id, file_revision, record_version, state, finished_at)
                VALUES (?, ?, 1, '历史失败论文', 'legacy-001', 0, 0, 'FAILED', now())
                """, id, kind);
        return id;
    }
}
