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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class UserAccessHttpTest {
    private static final String ADMIN = "integration";
    private static final String ADMIN_PASSWORD = "preserved-v2-password";
    private static final String PERSONAL_PASSWORD = "new-personal-password";
    private static final UUID OLD_JOB = UUID.fromString("9e2e7a18-70c5-4c70-b19d-4bf963f3e4d1");
    private static WorkspaceFixture fixture;

    @Autowired MockMvc mvc;

    @Autowired ObjectMapper json;
    private WorkspaceHttp http;

    @BeforeEach void requests() { http = new WorkspaceHttp(mvc, json); }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
        fixture.seedVersionTwoAccount(ADMIN, ADMIN_PASSWORD, OLD_JOB);
    }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @Test
    void newUserMustSetAnOwnPasswordBeforeAccessingTheWorkspace() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        var created = http.createUser(admin, "first.reader", "首次登录", "READ_ONLY");
        String temporary = created.path("temporaryPassword").asText();
        var pending = http.login("first.reader", temporary);
        var secondPending = http.login("first.reader", temporary);
        mvc.perform(get("/api/session").session(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true)).andExpect(jsonPath("$.permissions").isEmpty());
        mvc.perform(get("/api/papers").session(pending)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(http.write(post("/api/session/password"), pending, json.createObjectNode()
                        .put("currentPassword", temporary).put("newPassword", temporary)))
                .andExpect(status().isBadRequest());
        mvc.perform(http.write(post("/api/session/password"), pending, json.createObjectNode()
                        .put("currentPassword", temporary).put("newPassword", PERSONAL_PASSWORD)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(secondPending)).andExpect(status().isUnauthorized());
        var active = http.login("first.reader", PERSONAL_PASSWORD);
        mvc.perform(get("/api/session").session(active)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.permissions.length()").value(3));
        mvc.perform(get("/api/papers").session(active)).andExpect(status().isOk());
        var users = mvc.perform(get("/api/users").session(admin).param("q", "首次登录"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].username").value("first.reader")).andReturn();
        assertThat(users.getResponse().getContentAsString()).doesNotContain(temporary, "passwordHash", "temporaryPassword");
        mvc.perform(get("/api/users").session(admin).param("q", "FIRST.READER").param("role", "READ_ONLY")
                        .param("status", "ACTIVE").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/users").session(admin).param("q", "FIRST.READER").param("status", "DISABLED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void legacyUserCanSignInWithTheOriginalPasswordAndFindTheirExistingJob() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        mvc.perform(get("/api/session").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN)).andExpect(jsonPath("$.displayName").value(ADMIN))
                .andExpect(jsonPath("$.role").value("SYSTEM_ADMIN")).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(get("/api/jobs/{id}", OLD_JOB).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN));
    }

    @Test
    void permissionsProtectDirectCallsAndReflectARoleChangeOnAnExistingSession() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        var user = http.activate(admin, "changing.user", "维护账号", "READ_ONLY", PERSONAL_PASSWORD);
        for (String path : List.of("/api/papers", "/api/papers/legacy-001", "/api/papers/legacy-001/file",
                "/api/papers/legacy-001/blocks", "/api/sources?q=S123")) {
            mvc.perform(get(path).session(user)).andExpect(status().isOk());
        }
        for (String path : List.of("/api/papers/export", "/api/jobs", "/api/jobs/" + OLD_JOB,
                "/api/jobs/" + OLD_JOB + "/items", "/api/users", "/api/audit-events")) {
            mvc.perform(get(path).session(user)).andExpect(status().isForbidden());
        }
        mvc.perform(head("/api/papers/export").session(user)).andExpect(status().isForbidden());
        for (String path : List.of("/api/papers", "/api/papers/legacy-001/archive", "/api/papers/legacy-001/match",
                "/api/imports", "/api/imports/" + OLD_JOB + "/confirm", "/api/jobs", "/api/jobs/" + OLD_JOB + "/retry")) {
            mvc.perform(http.write(post(path), user, null)).andExpect(status().isForbidden());
        }
        mvc.perform(http.write(put("/api/papers/legacy-001/file"), user, null)).andExpect(status().isForbidden());
        mvc.perform(http.write(patch("/api/papers/legacy-001"), user, null)).andExpect(status().isForbidden());
        mvc.perform(http.write(delete("/api/papers/legacy-001/archive"), user, null)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").session(admin)).andExpect(status().isForbidden());

        mvc.perform(http.update(admin, "changing.user", "维护工作账号", "DATA_MAINTAINER")).andExpect(status().isOk());
        mvc.perform(get("/api/session").session(user)).andExpect(jsonPath("$.role").value("DATA_MAINTAINER"));
        mvc.perform(get("/api/jobs/{id}/items", OLD_JOB).session(user)).andExpect(status().isOk());
        mvc.perform(http.write(post("/api/jobs"), user, json.createObjectNode().put("kind", "METADATA").put("sourceId", "S123")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.username").value("changing.user"));
        mvc.perform(get("/api/users").session(user)).andExpect(status().isForbidden());
        mvc.perform(head("/api/papers/export").session(user)).andExpect(status().isForbidden());
        mvc.perform(head("/api/papers/export").session(admin)).andExpect(status().isOk());
    }

    @Test
    void parallelDemotionsLeaveOneEnabledAdministrator() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        var first = http.activate(admin, "race.first", "并发账号一", "SYSTEM_ADMIN", PERSONAL_PASSWORD);
        var second = http.activate(admin, "race.second", "并发账号二", "SYSTEM_ADMIN", PERSONAL_PASSWORD);
        mvc.perform(http.update(admin, ADMIN, ADMIN, "READ_ONLY")).andExpect(status().isOk());
        try {
            var start = new CountDownLatch(1);
            var one = http.update(first, "race.first", "并发账号一", "READ_ONLY");
            var two = http.update(second, "race.second", "并发账号二", "READ_ONLY");
            var requests = Executors.newFixedThreadPool(2);
            try {
                var demoteOne = requests.submit(() -> { start.await(); return mvc.perform(one).andReturn(); });
                var demoteTwo = requests.submit(() -> { start.await(); return mvc.perform(two).andReturn(); });
                start.countDown();
                var a = demoteOne.get(10, TimeUnit.SECONDS);
                var b = demoteTwo.get(10, TimeUnit.SECONDS);
                assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
                var conflict = a.getResponse().getStatus() == 409 ? a : b;
                assertThat(http.body(conflict).path("code").asText()).isEqualTo("LAST_SYSTEM_ADMIN");
                var survivor = conflict == a ? first : second;
                mvc.perform(get("/api/users").session(survivor).param("role", "SYSTEM_ADMIN").param("status", "ACTIVE"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            } finally {
                requests.shutdownNow();
            }
        } finally {
            restoreAdmin(first, second);
        }
    }

    @Test
    void userChangesAndDeniedExportsHaveSafeAuditEvents() throws Exception {
        var admin = http.login(ADMIN, ADMIN_PASSWORD);
        String username = "audit.changes";
        var created = http.createUser(admin, username, "初始显示名", "READ_ONLY");
        String temporary = created.path("temporaryPassword").asText();
        mvc.perform(http.update(admin, username, "新显示名", "DATA_MAINTAINER")).andExpect(status().isOk());
        var duplicate = json.createObjectNode().put("username", "AUDIT.CHANGES").put("displayName", "重复").put("role", "READ_ONLY");
        mvc.perform(http.write(post("/api/users"), admin, duplicate)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        var events = mvc.perform(get("/api/audit-events").session(admin).param("objectId", username))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andReturn();
        var items = http.body(events).path("items");
        assertThat(items).extracting(item -> item.path("action").asText())
                .containsExactly("USER_ROLE_CHANGE", "USER_DISPLAY_NAME_CHANGE", "USER_CREATE");
        var detail = mvc.perform(get("/api/audit-events/{id}", items.get(0).path("eventId").asLong()).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.previousRole").value("READ_ONLY"))
                .andExpect(jsonPath("$.summary.role").value("DATA_MAINTAINER")).andReturn();
        assertThat(detail.getResponse().getContentAsString()).doesNotContain(temporary, "passwordHash", "temporaryPassword");

        var pending = http.login(username, temporary);
        mvc.perform(head("/api/papers/export").session(pending).param("q", "private search term"))
                .andExpect(status().isForbidden());
        var denied = mvc.perform(get("/api/audit-events").session(admin).param("actor", username)
                        .param("action", "PAPER_CSV_EXPORT").param("outcome", "DENIED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn();
        long deniedId = http.body(denied).path("items").get(0).path("eventId").asLong();
        var deniedDetail = mvc.perform(get("/api/audit-events/{id}", deniedId).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.code").value("PASSWORD_CHANGE_REQUIRED")).andReturn();
        assertThat(deniedDetail.getResponse().getContentAsString()).doesNotContain("private search term", temporary);
    }

    private void restoreAdmin(MockHttpSession first, MockHttpSession second) throws Exception {
        for (var session : List.of(first, second)) {
            if (mvc.perform(http.update(session, ADMIN, ADMIN, "SYSTEM_ADMIN")).andReturn().getResponse().getStatus() == 200) return;
        }
        throw new AssertionError("No remaining administrator");
    }

}
