package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class RoleManagementHttpTest {
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
    void createsAnUnassignedCustomRoleAndListsItAlongsideBuiltInRoles() throws Exception {
        var role = create("查阅及导出", "PAPER_READ", "PAPER_CSV_EXPORT");
        String id = role.path("id").asText();
        mvc.perform(get("/api/roles").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == 'SYSTEM_ADMIN')].builtIn").value(true))
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].builtIn").value(false));
        mvc.perform(get("/api/roles/{id}", id).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("查阅及导出"))
                .andExpect(jsonPath("$.description").value("管理用例验收"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.members").isEmpty())
                .andExpect(jsonPath("$.userCount").value(0));
    }

    @Test
    void changesAnExistingSessionsPermissionsAndProtectsDisabledMembers() throws Exception {
        String id = create("可执行论文导出的角色", "PAPER_READ", "PAPER_CSV_EXPORT").path("id").asText();
        String username = "roles.live";
        var member = http.activate(admin, username, "导出成员", "READ_ONLY", "custom-personal-password");
        mvc.perform(head("/api/papers/export").session(member)).andExpect(status().isForbidden());
        mvc.perform(http.update(admin, username, "导出成员", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(id));
        mvc.perform(get("/api/users").session(admin).param("role", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value(username));
        mvc.perform(get("/api/session").session(member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(id)).andExpect(jsonPath("$.permissions.length()").value(2));
        mvc.perform(get("/api/roles").session(member)).andExpect(status().isForbidden());
        var csv = mvc.perform(get("/api/papers/export").session(member)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(csv)).andExpect(status().isOk()).andExpect(content().string(
                org.hamcrest.Matchers.containsString("legacy-001")));

        mvc.perform(http.write(put("/api/roles/{id}", id), admin, profile("导出成员", "PAPER_READ").put("expectedUserCount", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_ASSIGNMENTS_CHANGED"));
        mvc.perform(http.write(put("/api/roles/{id}", id), admin, profile("导出成员", "PAPER_READ").put("expectedUserCount", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members[0].username").value(username));
        mvc.perform(get("/api/papers/export").session(member)).andExpect(status().isForbidden());
        mvc.perform(http.write(put("/api/users/{username}/status", username), admin,
                json.createObjectNode().put("status", "DISABLED"))).andExpect(status().isOk());
        mvc.perform(get("/api/roles/{id}", id).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].status").value("DISABLED")).andExpect(jsonPath("$.userCount").value(1));
        mvc.perform(http.write(delete("/api/roles/{id}", id), admin, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
        mvc.perform(http.update(admin, username, "导出成员", "READ_ONLY")).andExpect(status().isOk());
        mvc.perform(http.write(delete("/api/roles/{id}", id), admin, null)).andExpect(status().isNoContent());
        mvc.perform(get("/api/roles/{id}", id).session(admin)).andExpect(status().isNotFound());
        assertChangesAudited(id, username);
    }

    @Test
    void validatesPermissionDependenciesAndReservesBuiltInRoles() throws Exception {
        mvc.perform(get("/api/roles").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionGroups[1].permissions[1].code").value("PDF_PARSE"))
                .andExpect(jsonPath("$.permissionGroups[1].permissions[1].requires[0]").value("JOB_READ"));
        for (String permission : new String[]{"CONTENT_READ", "PAPER_WRITE", "PDF_PARSE", "FULL_TEXT_IMPORT", "OPENALEX_MANAGE", "CAUSAL_EXTRACT", "SYSTEM_MANAGE"}) {
            mvc.perform(http.write(post("/api/roles"), admin, profile("无效权限组合", permission)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        var update = profile("更改内置角色", "PAPER_READ").put("expectedUserCount", 0);
        mvc.perform(http.write(put("/api/roles/READ_ONLY"), admin, update))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BUILT_IN_ROLE"));
        mvc.perform(http.write(delete("/api/roles/READ_ONLY"), admin, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BUILT_IN_ROLE"));
        mvc.perform(get("/api/roles/READ_ONLY").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder("PAPER_READ", "CONTENT_READ", "CAUSAL_READ")));
    }

    @Test
    void rollsBackRoleChangesAndAssignmentsWhenSuccessAuditingFails() throws Exception {
        String username = "roles.atomic";
        String id = create("原角色名称", "PAPER_READ").path("id").asText();
        http.createUser(admin, username, "单事务测试用户", "READ_ONLY");
        jdbc.execute("""
                ALTER TABLE v2_audit_event ADD CONSTRAINT reject_role_success_event CHECK (
                    (action <> 'ROLE_UPDATE' OR summary->>'roleName' <> '拦截角色变更')
                    AND (action <> 'USER_ROLE_CHANGE' OR object_id <> 'roles.atomic'))
                """);
        try {
            var profile = profile("拦截角色变更", "PAPER_CSV_EXPORT").put("expectedUserCount", 0);
            mvc.perform(http.write(put("/api/roles/{id}", id), admin, profile)).andExpect(status().isInternalServerError());
            mvc.perform(http.update(admin, username, "单事务测试用户", id)).andExpect(status().isInternalServerError());
            mvc.perform(get("/api/roles/{id}", id).session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("原角色名称")).andExpect(jsonPath("$.permissions[0]").value("PAPER_READ"))
                    .andExpect(jsonPath("$.userCount").value(0));
            mvc.perform(get("/api/users").session(admin).param("q", username)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].role").value("READ_ONLY"));
        } finally {
            jdbc.execute("ALTER TABLE v2_audit_event DROP CONSTRAINT reject_role_success_event");
        }
    }

    @Test
    void concurrentDeletionAndAssignmentLeaveNoDanglingRole() throws Exception {
        String username = "roles.concurrent";
        String id = create("并发分配角色").path("id").asText();
        http.createUser(admin, username, "并发分配用户", "READ_ONLY");
        var assign = http.update(admin, username, "并发分配用户", id);
        var remove = http.write(delete("/api/roles/{id}", id), admin, null);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var assignment = executor.submit(() -> { start.await(); return mvc.perform(assign).andReturn().getResponse().getStatus(); });
            var deletion = executor.submit(() -> { start.await(); return mvc.perform(remove).andReturn().getResponse().getStatus(); });
            start.countDown();
            int assigned = assignment.get(5, TimeUnit.SECONDS);
            int deleted = deletion.get(5, TimeUnit.SECONDS);
            if (assigned == 200) {
                assertThat(deleted).isEqualTo(409);
                mvc.perform(get("/api/roles/{id}", id).session(admin)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.members[0].username").value(username));
            } else {
                assertThat(assigned).isEqualTo(404);
                assertThat(deleted).isEqualTo(204);
                mvc.perform(get("/api/users").session(admin).param("q", username)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].role").value("READ_ONLY"));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertChangesAudited(String id, String username) throws Exception {
        var events = mvc.perform(get("/api/audit-events").session(admin).param("objectType", "ROLE").param("objectId", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andReturn();
        var items = http.body(events).path("items");
        assertThat(items).extracting(item -> item.path("action").asText())
                .containsExactly("ROLE_DELETE", "ROLE_UPDATE", "ROLE_CREATE");
        mvc.perform(get("/api/audit-events/{id}", items.get(1).path("eventId").asLong()).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.previousPermissions[0]").value("PAPER_CSV_EXPORT"))
                .andExpect(jsonPath("$.summary.assignedUsers").value(1));
        mvc.perform(get("/api/audit-events").session(admin).param("objectId", username).param("action", "USER_ROLE_CHANGE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }

    private JsonNode create(String name, String... permissions) throws Exception {
        return http.body(mvc.perform(http.write(post("/api/roles"), admin, profile(name, permissions)))
                .andExpect(status().isCreated()).andReturn());
    }

    private ObjectNode profile(String name, String... permissions) {
        var profile = json.createObjectNode().put("name", name).put("description", "管理用例验收");
        var codes = profile.putArray("permissions");
        for (String permission : permissions) codes.add(permission);
        return profile;
    }
}
