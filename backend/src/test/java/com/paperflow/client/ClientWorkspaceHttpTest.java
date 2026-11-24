package com.paperflow.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.main.Paperflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static com.paperflow.client.ClientWorkspaceFixture.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Paperflow.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ClientWorkspaceHttpTest {
    private static final String ROOT = "/api/client";
    private static final String TASKS = ROOT + "/citations/tasks";
    private static final String CUSTOM = "CUSTOM_00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        ClientWorkspaceFixture.configure(properties);
    }

    @BeforeEach
    void seedUsers() {
        jdbc.update("""
                INSERT INTO v2_role (role_id, name, description, permissions)
                VALUES (?, 'Papers only', '', ARRAY['PAPER_READ']) ON CONFLICT DO NOTHING
                """, CUSTOM);
        String hash = passwords.encode(PASSWORD);
        for (String user : List.of("researcher", "colleague", "papers-only")) {
            jdbc.update("""
                    INSERT INTO v2_admin (username, display_name, password_hash, role)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, user, user, hash, user.equals("papers-only") ? CUSTOM : "RESEARCHER");
        }
    }

    @Test
    void researchersCanUseClientCatalogButCannotOpenManagement() throws Exception {
        var user = login("researcher");
        mvc.perform(get(ROOT + "/modules").session(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].status").value("STUB"))
                .andExpect(jsonPath("$.modules[1].status").value("COMING_SOON"))
                .andExpect(jsonPath("$.modules[2].id").value("writing"));
        mvc.perform(get(ROOT + "/journals").session(user).param("q", "applied").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("S123"))
                .andExpect(jsonPath("$.items[0].publishedCount").value(1));
        mvc.perform(get(TASKS).session(user)).andExpect(status().isOk());
        mvc.perform(get("/api/papers").session(user)).andExpect(status().isForbidden());
        var restricted = login("papers-only");
        for (var path : List.of(ROOT + "/modules", ROOT + "/journals", TASKS)) {
            mvc.perform(get(path).session(restricted)).andExpect(status().isForbidden());
        }
    }

    @Test
    void stubTaskStreamsEnrichedAlternativesAndPersistsReview() throws Exception {
        ClientWorkspaceFixture.seedBibliography(jdbc);
        var user = login("researcher");
        var task = create(user);
        var path = TASKS + "/" + task.path("taskId").asText();
        var live = sse(path, user);
        assertThat(live).contains("event:progress", "event:complete", "SUCCEEDED");
        assertThat(sse(path, user)).isEqualTo(live);
        var detail = body(mvc.perform(get(path).session(user)).andExpect(status().isOk()).andReturn());
        var statement = detail.path("statements").get(0).path("statementId").asText();
        var alternatives = detail.path("referencesByStatement").path(statement);
        assertThat(alternatives.size()).isGreaterThanOrEqualTo(6);
        var candidate = alternatives.get(5);
        assertThat(candidate.path("title").asText()).isEqualTo("Local alternative title");
        assertThat(candidate.path("paperId").asText()).isEqualTo("citation-local");
        assertThat(candidate.path("metadataSource").asText()).isEqualTo("LOCAL_DB_AND_ENGINE");
        assertThat(candidate.path("year").asInt()).isEqualTo(1970);
        assertThat(candidate.path("authors").get(0).asText()).isEqualTo("Jane Local");
        var review = json.createObjectNode().put("citationStyle", "ieee");
        review.putObject("selectedReferenceIds").putArray(statement).add(candidate.path("referenceId").asText());
        mvc.perform(write(patch(path + "/review"), user, review)).andExpect(status().isOk())
                .andExpect(jsonPath("$.citationStyle").value("ieee"))
                .andExpect(jsonPath("$.annotatedText").value("Trade grows [1]."));
        assertExports(path, user);
        assertIsolated(path, login("colleague"));
        mvc.perform(write(delete(path), user, null)).andExpect(status().isNoContent());
        mvc.perform(get(path).session(user)).andExpect(status().isNotFound());
    }

    @Test
    void activeLimitAndCancellationProtectTaskState() throws Exception {
        var user = login("researcher");
        var task = create(user);
        String path = TASKS + "/" + task.path("taskId").asText();
        mvc.perform(write(post(TASKS), user, input())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CITATION_CONFLICT"));
        mvc.perform(write(delete(path), user, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CITATION_TASK_ACTIVE"));
        mvc.perform(write(post(path + "/cancel"), user, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(sse(path, user)).contains("event:complete", "CANCELLED");
        mvc.perform(write(post(path + "/cancel"), user, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CITATION_TASK_TERMINAL"));
        mvc.perform(write(delete(path), user, null)).andExpect(status().isNoContent());
    }

    @Test
    void startupRecoversAnInterruptedTaskAndPersistsItsTerminalEvent() throws Exception {
        var user = login("restart-user");
        var path = TASKS + "/" + ClientWorkspaceFixture.INTERRUPTED;
        mvc.perform(get(path).session(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("INTERRUPTED"))
                .andExpect(jsonPath("$.events[0].eventType").value("FAILED"));
        assertThat(sse(path, user)).contains("event:progress", "event:complete", "FAILED");
    }

    private void assertExports(String path, MockHttpSession user) throws Exception {
        for (String format : List.of("txt", "md", "bibtex")) {
            var result = mvc.perform(get(path + "/export").session(user).param("format", format).param("style", "apa"))
                    .andExpect(status().isOk()).andExpect(header().exists("Content-Disposition")).andReturn();
            assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("Local alternative title");
        }
        mvc.perform(get(path).session(user)).andExpect(jsonPath("$.citationStyle").value("ieee"));
    }

    private void assertIsolated(String path, MockHttpSession other) throws Exception {
        for (String suffix : List.of("", "/events", "/export?format=txt")) {
            var request = get(path + suffix).session(other);
            if (suffix.equals("/events")) request.accept(MediaType.TEXT_EVENT_STREAM);
            mvc.perform(request).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CITATION_TASK_NOT_FOUND"));
        }
        mvc.perform(write(post(path + "/cancel"), other, null)).andExpect(status().isNotFound());
        mvc.perform(write(patch(path + "/review"), other, json.createObjectNode())).andExpect(status().isNotFound());
        mvc.perform(write(delete(path), other, null)).andExpect(status().isNotFound());
        mvc.perform(get(TASKS).session(other)).andExpect(jsonPath("$.total").value(0));
    }

    private String sse(String path, MockHttpSession user) throws Exception {
        var result = mvc.perform(get(path + "/events").session(user)).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(7000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode create(MockHttpSession user) throws Exception {
        return body(mvc.perform(write(post(TASKS), user, input())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED")).andReturn());
    }

    private JsonNode input() {
        return json.createObjectNode().put("text", "Trade grows.").put("citationStyle", "apa");
    }

    private MockHttpSession login(String username) throws Exception {
        var data = json.createObjectNode().put("username", username).put("password", PASSWORD);
        return (MockHttpSession) mvc.perform(write(post("/api/session"), new MockHttpSession(), data))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession session, JsonNode data)
            throws Exception {
        var csrf = body(mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn());
        request.session(session).header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return data == null ? request : request.contentType("application/json").content(data.toString());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
