package com.paperflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.main.Paperflow;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {Paperflow.class, AuditHttpTest.FlushFailure.class})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PAPERFLOW_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class AuditHttpTest {
    private static WorkspaceFixture fixture;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        fixture = WorkspaceFixture.configure(properties);
    }

    @AfterAll static void stop() { if (fixture != null) fixture.close(); }

    @Test
    void administratorCanTraceAFilteredCsvWithoutExposingSearchOrPaperContent() throws Exception {
        var admin = login();
        var export = mvc.perform(get("/api/papers/export").session(admin)
                        .param("q", "Legacy paper").param("sourceId", "S123").param("yearFrom", "2024")
                        .param("status", "PUBLISHED"))
                .andExpect(request().asyncStarted()).andReturn();
        var csv = mvc.perform(asyncDispatch(export))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=paperflow-papers.csv"))
                .andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .startsWith("\uFEFFpaper_id,source_id,year,paper_title,authors,doi,status\r\n")
                .contains("legacy-001,S123,2024,Legacy paper,").doesNotContain("Existing published text");

        var page = mvc.perform(get("/api/audit-events").session(admin)
                        .param("action", "PAPER_CSV_EXPORT").param("outcome", "SUCCEEDED").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actor").value("integration"))
                .andExpect(jsonPath("$.items[0].occurredAt").isString())
                .andExpect(jsonPath("$.items[0].objectType").value("PAPER_COLLECTION"))
                .andExpect(jsonPath("$.items[0].objectId").value("papers"))
                .andReturn();
        long id = body(page).path("items").get(0).path("eventId").asLong();
        var detail = mvc.perform(get("/api/audit-events/{eventId}", id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.exportedRows").value(1))
                .andExpect(jsonPath("$.summary.searchApplied").value(true))
                .andExpect(jsonPath("$.summary.sourceId").value("S123"))
                .andExpect(jsonPath("$.summary.yearFrom").value(2024))
                .andExpect(jsonPath("$.summary.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.summary.archived").value(false)).andReturn();
        assertThat(detail.getResponse().getContentAsString()).doesNotContain(
                "Legacy paper", "legacy-001", "Existing published text", "paper_title",
                "workspace-test-password");
        assertThat(body(detail).size()).isEqualTo(8);
        assertThat(body(detail).path("summary").size()).isEqualTo(6);
    }

    @Test
    void failedExportsCanBeFilteredAndPagedWithoutRecordingAFalseSuccess() throws Exception {
        var admin = login();
        mvc.perform(get("/api/papers/export").session(admin).param("yearFrom", "invalid-year"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        var export = mvc.perform(get("/api/papers/export").session(admin).header("Audit-Test-Fail-Flush", "true"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(export.getAsyncResult()).isInstanceOf(IOException.class);
        var firstPage = searchFailed(admin, 0);
        var first = firstPage.path("items").get(0);
        var second = searchFailed(admin, 1).path("items").get(0);
        assertThat(firstPage.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("eventId").asLong()).isGreaterThan(second.path("eventId").asLong());
        assertFailedDetail(admin, first, "EXPORT_FAILED");
        assertFailedDetail(admin, second, "INVALID_INPUT");

        mvc.perform(head("/api/papers/export").session(admin)).andExpect(status().isOk())
                .andExpect(request().asyncNotStarted()).andExpect(content().string(""));
        var time = first.path("occurredAt").asText();
        mvc.perform(get("/api/audit-events").session(admin).param("from", time).param("to", time)
                        .param("actor", "integration").param("action", "PAPER_CSV_EXPORT")
                        .param("objectType", "PAPER_COLLECTION").param("objectId", "papers").param("outcome", "FAILED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/audit-events").session(admin).param("from", time).param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/audit-events").session(admin).param("from", time).param("to", "2024-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/audit-events").session(admin).param("objectId", "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void auditEndpointsRequireSystemAdministratorAndOnlyExposeReads() throws Exception {
        var http = new WorkspaceHttp(mvc, json);
        var reader = http.activate(login(), "audit.reader", "审计只读账号", "READ_ONLY", "reader-personal-password");
        for (String path : List.of("/api/audit-events", "/api/audit-events/1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
            mvc.perform(get(path).session(reader)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        var admin = login();
        mvc.perform(get("/api/audit-events/{eventId}", Long.MAX_VALUE).session(admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUDIT_EVENT_NOT_FOUND"));
        var csrf = body(mvc.perform(get("/api/csrf").session(admin)).andReturn());
        mvc.perform(post("/api/audit-events").session(admin)
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private JsonNode searchFailed(MockHttpSession admin, int page) throws Exception {
        return body(mvc.perform(get("/api/audit-events").session(admin)
                        .param("action", "PAPER_CSV_EXPORT").param("outcome", "FAILED")
                        .param("size", "1").param("page", String.valueOf(page)))
                .andExpect(status().isOk()).andReturn());
    }

    private void assertFailedDetail(MockHttpSession admin, JsonNode event, String code) throws Exception {
        var detail = mvc.perform(get("/api/audit-events/{eventId}", event.path("eventId").asLong()).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAILED")).andReturn();
        assertThat(body(detail).path("summary")).isEqualTo(json.createObjectNode().put("code", code));
    }

    private MockHttpSession login() throws Exception {
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        var token = body(csrf);
        mvc.perform(post("/api/session").session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .contentType("application/json").content("""
                                {"username":"integration","password":"workspace-test-password"}
                                """)).andExpect(status().isOk());
        return session;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlushFailure {
        @Bean
        Filter failCsvFlush() {
            return (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (http.getHeader("Audit-Test-Fail-Flush") == null) {
                    chain.doFilter(request, response);
                    return;
                }
                chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) response) {
                    @Override
                    public ServletOutputStream getOutputStream() throws IOException {
                        var original = super.getOutputStream();
                        return new ServletOutputStream() {
                            @Override public boolean isReady() { return original.isReady(); }
                            @Override public void setWriteListener(WriteListener listener) { original.setWriteListener(listener); }
                            @Override public void write(int value) throws IOException { original.write(value); }
                            @Override public void flush() throws IOException { throw new IOException("fixture flush failure"); }
                        };
                    }
                });
            };
        }
    }
}
