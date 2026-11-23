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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class UserLifecycleHttpTest {
    private static final String ADMIN = "integration";
    private static final String ADMIN_PASSWORD = "workspace-test-password";
    private static final String PERSONAL = "original-personal-password";
    private static final String REPLACEMENT = "replacement-personal-password";
    private static WorkspaceFixture fixture;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    private WorkspaceHttp http;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) { fixture = WorkspaceFixture.configure(properties); }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @BeforeEach void requests() { http = new WorkspaceHttp(mvc, json); }

    @Test
    void disablingRevokesSessionsAndRetainsSubmittedTasksThroughReactivation() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        String username = "lifecycle.maintainer";
        var old = http.activate(admin, username, "任务维护用户", "DATA_MAINTAINER", PERSONAL);
        String jobId;
        try (var connection = dataSource.getConnection(); var lock = connection.createStatement()) {
            connection.setAutoCommit(false);
            lock.execute("LOCK TABLE v2_openalex_work IN ACCESS EXCLUSIVE MODE");
            var submitted = mvc.perform(http.write(post("/api/jobs"), old,
                            json.createObjectNode().put("kind", "METADATA").put("sourceId", "S123")))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.username").value(username)).andReturn();
            jobId = http.body(submitted).path("jobId").asText();
            mvc.perform(statusRequest(admin, username, "DISABLED")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.username").value(username));
            connection.commit();
        }
        assertRevoked(old);
        assertInvalidLogin(username, PERSONAL);
        awaitJob(admin, jobId, username);
        mvc.perform(statusRequest(admin, username, "DISABLED")).andExpect(status().isOk());
        mvc.perform(statusRequest(admin, username, "ACTIVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertRevoked(old);
        var restored = http.login(username, PERSONAL);
        mvc.perform(get("/api/jobs/{id}", jobId).session(restored)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username)).andExpect(jsonPath("$.state").value("SUCCEEDED"));
        var enabled = auditDetail(admin, username, "USER_ENABLE");
        assertThat(enabled.path("summary").path("previousAccountStatus").asText()).isEqualTo("DISABLED");
        assertThat(auditDetail(admin, username, "USER_DISABLE").path("summary").path("accountStatus").asText())
                .isEqualTo("DISABLED");
    }

    @Test
    void resettingIssuesOneTemporaryPasswordAndBothPasswordChangesRevokeOldSessions() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        String username = "lifecycle.reader";
        var old = http.activate(admin, username, "密码重置用户", "READ_ONLY", PERSONAL);
        mvc.perform(resetRequest(old, username)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(statusRequest(old, username, "DISABLED")).andExpect(status().isForbidden());
        mvc.perform(resetRequest(admin, "missing.user")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        var reset = mvc.perform(resetRequest(admin, username)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.user.username").value(username)).andReturn();
        String temporary = http.body(reset).path("temporaryPassword").asText();
        assertThat(temporary).hasSizeGreaterThanOrEqualTo(12).isNotEqualTo(PERSONAL);
        assertRevoked(old);
        assertInvalidLogin(username, PERSONAL);
        var pending = http.login(username, temporary);
        var otherPending = http.login(username, temporary);
        mvc.perform(get("/api/papers").session(pending)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(http.write(post("/api/session/password"), pending, json.createObjectNode()
                        .put("currentPassword", temporary).put("newPassword", REPLACEMENT)))
                .andExpect(status().isNoContent());
        assertRevoked(otherPending);
        assertInvalidLogin(username, temporary);
        mvc.perform(get("/api/papers").session(http.login(username, REPLACEMENT))).andExpect(status().isOk());
        var audit = auditDetail(admin, username, "USER_PASSWORD_RESET");
        assertThat(audit.path("summary").toString()).isEqualTo("{\"mustChangePassword\":true}");
        assertThat(audit.toString()).doesNotContain(temporary, PERSONAL, REPLACEMENT, "passwordHash", "credentialVersion", "temporaryPassword");
    }

    @Test
    void demotionAndDisableOfDifferentAdministratorsShareTheLastAdministratorGuard() throws Exception {
        var first = http.login(ADMIN, ADMIN_PASSWORD);
        String username = "lifecycle.admin";
        var second = http.activate(first, username, "并发系统管理员", "SYSTEM_ADMIN", PERSONAL);
        var requests = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var disable = statusRequest(second, username, "DISABLED");
            var demote = http.update(first, ADMIN, ADMIN, "READ_ONLY");
            var one = requests.submit(() -> { start.await(); return mvc.perform(disable).andReturn(); });
            var two = requests.submit(() -> { start.await(); return mvc.perform(demote).andReturn(); });
            start.countDown();
            var disabled = one.get(10, TimeUnit.SECONDS);
            var demoted = two.get(10, TimeUnit.SECONDS);
            assertThat(List.of(disabled.getResponse().getStatus(), demoted.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            boolean remainsSecond = disabled.getResponse().getStatus() == 409;
            assertThat(http.body(remainsSecond ? disabled : demoted).path("code").asText()).isEqualTo("LAST_SYSTEM_ADMIN");
            var survivor = remainsSecond ? second : first;
            mvc.perform(get("/api/users").session(survivor).param("role", "SYSTEM_ADMIN").param("status", "ACTIVE"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            if (remainsSecond) mvc.perform(http.update(survivor, ADMIN, ADMIN, "SYSTEM_ADMIN")).andExpect(status().isOk());
            mvc.perform(statusRequest(first, username, "DISABLED")).andExpect(status().isOk());
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void anAuditFailurePreservesTheAccountAndItsExistingSession() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        String username = "lifecycle.rollback";
        var existing = http.activate(admin, username, "审计事务账号", "READ_ONLY", PERSONAL);
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE v2_audit_event ADD CONSTRAINT reject_lifecycle_event CHECK (object_id <> 'lifecycle.rollback') NOT VALID");
            try {
                mvc.perform(resetRequest(admin, username)).andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
                mvc.perform(statusRequest(admin, username, "DISABLED")).andExpect(status().isInternalServerError());
                mvc.perform(get("/api/session").session(existing)).andExpect(status().isOk());
                http.login(username, PERSONAL);
            } finally {
                sql.execute("ALTER TABLE v2_audit_event DROP CONSTRAINT reject_lifecycle_event");
            }
        }
    }

    private MockHttpServletRequestBuilder statusRequest(MockHttpSession actor, String username, String state) throws Exception {
        return http.write(put("/api/users/{username}/status", username), actor, json.createObjectNode().put("status", state));
    }

    private MockHttpServletRequestBuilder resetRequest(MockHttpSession actor, String username) throws Exception {
        return http.write(post("/api/users/{username}/reset-password", username), actor, null);
    }

    private void assertRevoked(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/papers").session(session)).andExpect(status().isUnauthorized());
    }

    private void assertInvalidLogin(String username, String password) throws Exception {
        mvc.perform(http.write(post("/api/session"), new MockHttpSession(), json.createObjectNode()
                        .put("username", username).put("password", password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_LOGIN"));
    }

    private JsonNode auditDetail(MockHttpSession admin, String username, String action) throws Exception {
        var events = mvc.perform(get("/api/audit-events").session(admin).param("objectId", username).param("action", action)
                        .param("outcome", "SUCCEEDED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].actor").value(ADMIN)).andReturn();
        var id = http.body(events).path("items").get(0).path("eventId").asLong();
        return http.body(mvc.perform(get("/api/audit-events/{id}", id).session(admin)).andExpect(status().isOk()).andReturn());
    }

    private void awaitJob(MockHttpSession admin, String id, String username) throws Exception {
        MvcResult result = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            result = mvc.perform(get("/api/jobs/{id}", id).session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(username)).andReturn();
            if (http.body(result).path("state").asText().equals("SUCCEEDED")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Submitted task did not finish: " + http.body(result));
    }
}
