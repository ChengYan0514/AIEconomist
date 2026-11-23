package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

final class WorkspaceHttp {
    private final MockMvc mvc;
    private final ObjectMapper json;

    WorkspaceHttp(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    MockHttpServletRequestBuilder update(MockHttpSession admin, String username, String displayName, String role)
            throws Exception {
        return write(patch("/api/users/{username}", username), admin, json.createObjectNode()
                .put("displayName", displayName).put("role", role));
    }

    MockHttpSession activate(MockHttpSession admin, String username, String displayName, String role,
                             String personalPassword) throws Exception {
        String temporary = createUser(admin, username, displayName, role).path("temporaryPassword").asText();
        var pending = login(username, temporary);
        mvc.perform(write(post("/api/session/password"), pending, json.createObjectNode()
                        .put("currentPassword", temporary).put("newPassword", personalPassword)))
                .andExpect(status().isNoContent());
        return login(username, personalPassword);
    }

    JsonNode createUser(MockHttpSession admin, String username, String displayName, String role) throws Exception {
        return body(mvc.perform(write(post("/api/users"), admin, json.createObjectNode()
                        .put("username", username).put("displayName", displayName).put("role", role)))
                .andExpect(status().isCreated()).andReturn());
    }

    MockHttpSession login(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(write(post("/api/session"), session, json.createObjectNode()
                        .put("username", username).put("password", password)))
                .andExpect(status().isOk());
        return session;
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession session, JsonNode data)
            throws Exception {
        var csrf = body(mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn());
        request.session(session).header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return data == null ? request : request.contentType("application/json").content(data.toString());
    }

    JsonNode auditSummary(MockHttpSession admin, String object, String action, String outcome, String actor) throws Exception {
        var events = body(mvc.perform(get("/api/audit-events").session(admin).param("action", action)
                        .param("actor", actor).param("objectId", object).param("outcome", outcome))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].occurredAt").isString()).andReturn());
        return body(mvc.perform(get("/api/audit-events/{id}", events.path("items").get(0).path("eventId").asLong())
                .session(admin)).andExpect(status().isOk()).andReturn()).path("summary");
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
