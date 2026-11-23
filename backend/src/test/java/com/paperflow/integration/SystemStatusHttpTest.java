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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class, properties = "spring.servlet.multipart.max-file-size=150MB")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class SystemStatusHttpTest {
    private static final String PATH = "/api/system-status";
    private static WorkspaceFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private WorkspaceHttp http;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @BeforeEach
    void setup() {
        http = new WorkspaceHttp(mvc, json);
    }

    @AfterAll
    static void stop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void returnsTimestampedChecksAndOnlyAllowlistedEffectiveConfiguration() throws Exception {
        var response = refresh(loginAdmin());
        assertKeys(response, "checks", "configuration");
        assertThat(checks(response).values()).allSatisfy(check -> assertThat(check.path("state").asText()).isEqualTo("HEALTHY"));
        var config = response.path("configuration");
        assertKeys(config, "version", "maxOriginalBytes", "maxZipBytes", "maxJobTargets");
        assertThat(config.path("version").asText()).matches("\\d+\\.\\d+\\.\\d+.*");
        assertThat(config.path("maxOriginalBytes").asLong()).isEqualTo(104857600);
        assertThat(config.path("maxZipBytes").asLong()).isEqualTo(157286400);
        assertThat(config.path("maxJobTargets").asInt()).isEqualTo(10000);
        assertThat(response.toString()).doesNotContain(fixture.mineru(), fixture.jdbcUrl(), fixture.snapshot(),
                fixture.legacy().toString(), fixture.storage().toString(), "workspace-test-password");
        assertThat(fixture.storage()).isEmptyDirectory();
        assertThat(fixture.legacy()).isDirectoryContaining(path -> path.equals(fixture.legacy().resolve("original")));
        assertThat(Files.readString(fixture.legacy().resolve("original/legacy.pdf"))).isEqualTo(WorkspaceFixture.PDF);
    }

    @Test
    void slowMineruAndMissingSnapshotTableLeaveOtherChecksAvailable() throws Exception {
        var admin = loginAdmin();
        String authors = fixture.snapshot() + ".authors";
        jdbc.execute("ALTER TABLE " + authors + " RENAME TO authors_unavailable");
        fixture.delayMineruHealth(5000);
        try {
            Instant started = Instant.now();
            var report = checks(refresh(admin));
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(4));
            assertThat(report.get("MINERU").path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(report.get("MINERU").path("message").asText()).isEqualTo("连接超时");
            assertThat(report.get("OPENALEX_SNAPSHOT").path("state").asText()).isEqualTo("UNAVAILABLE");
            for (String dependency : List.of("POSTGRESQL", "CURRENT_STORAGE", "LEGACY_STORAGE")) {
                assertThat(report.get(dependency).path("state").asText()).isEqualTo("HEALTHY");
            }
        } finally {
            fixture.delayMineruHealth(0);
            jdbc.execute("ALTER TABLE " + fixture.snapshot() + ".authors_unavailable RENAME TO authors");
        }
    }

    @Test
    void reportsUnusableStorageWithoutWritingToTheLegacyLocation() throws Exception {
        var admin = loginAdmin();
        var legacy = fixture.legacy();
        var movedLegacy = legacy.resolveSibling("legacy-offline");
        var storage = fixture.storage();
        Files.deleteIfExists(storage);
        Files.writeString(storage, "existing file");
        Files.move(legacy, movedLegacy);
        try {
            var report = checks(refresh(admin));
            assertThat(report.get("CURRENT_STORAGE").path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(report.get("LEGACY_STORAGE").path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(report.get("POSTGRESQL").path("state").asText()).isEqualTo("HEALTHY");
            assertThat(Files.readString(storage)).isEqualTo("existing file");
            assertThat(legacy).doesNotExist();
        } finally {
            Files.move(movedLegacy, legacy);
            Files.delete(storage);
        }
    }

    @Test
    void slowApplicationQueryDoesNotBlockOtherChecks() throws Exception {
        var admin = loginAdmin();
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE v2_paper IN ACCESS EXCLUSIVE MODE");
            try {
                Instant started = Instant.now();
                var report = checks(refresh(admin));
                assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(4));
                assertThat(report.get("POSTGRESQL").path("state").asText()).isEqualTo("UNAVAILABLE");
                assertThat(report.get("OPENALEX_SNAPSHOT").path("state").asText()).isEqualTo("HEALTHY");
                assertThat(report.get("CURRENT_STORAGE").path("state").asText()).isEqualTo("HEALTHY");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void restrictsDirectRequestsToSystemAdministrators() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        var admin = loginAdmin();
        for (String role : List.of("READ_ONLY", "DATA_MAINTAINER")) {
            var user = http.activate(admin, "status." + role.toLowerCase(java.util.Locale.ROOT), "检查权限", role,
                    "personal-test-password");
            mvc.perform(get(PATH).session(user)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(head(PATH).session(user)).andExpect(status().isForbidden());
        }
    }

    private JsonNode refresh(MockHttpSession session) throws Exception {
        return http.body(mvc.perform(get(PATH).session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }

    private MockHttpSession loginAdmin() throws Exception {
        return http.login("integration", "workspace-test-password");
    }

    private Map<String, JsonNode> checks(JsonNode response) {
        Map<String, JsonNode> result = new HashMap<>();
        response.path("checks").forEach(check -> {
            assertKeys(check, "dependency", "state", "checkedAt", "message");
            assertThat(Instant.parse(check.path("checkedAt").asText())).isBeforeOrEqualTo(Instant.now());
            assertThat(check.path("message").asText()).isNotBlank();
            assertThat(result.put(check.path("dependency").asText(), check)).isNull();
        });
        assertThat(result.keySet()).containsExactlyInAnyOrder("POSTGRESQL", "MINERU", "CURRENT_STORAGE", "LEGACY_STORAGE", "OPENALEX_SNAPSHOT");
        return result;
    }

    private void assertKeys(JsonNode object, String... fields) {
        assertThat((Iterable<String>) object::fieldNames).containsExactlyInAnyOrder(fields);
    }
}
