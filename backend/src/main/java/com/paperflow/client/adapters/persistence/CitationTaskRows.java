package com.paperflow.client.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.citation.model.CitationTaskSummary;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.citation.model.RetrievalPolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

final class CitationTaskRows {
    private final ObjectMapper json;

    CitationTaskRows(ObjectMapper json) {
        this.json = json;
    }

    CitationTask task(ResultSet rs, int row) throws SQLException {
        var status = new CitationTask.State(CitationTaskStatus.valueOf(rs.getString("status")),
                CitationStage.valueOf(rs.getString("stage")), rs.getDouble("progress"), rs.getString("engine_run_id"),
                rs.getString("error_code"), rs.getString("error_message"), time(rs, "updated_at"), time(rs, "finished_at"));
        return new CitationTask(rs.getObject("task_id", UUID.class), rs.getString("title"), rs.getString("original_text"),
                CitationStyle.parse(rs.getString("citation_style")), read(rs.getString("retrieval_policy"), RetrievalPolicy.class),
                rs.getString("engine_provider"), time(rs, "created_at"), status,
                read(rs.getString("result_payload"), CitationResult.class), rs.getString("annotated_text"),
                rs.getString("bibliography"));
    }

    CitationTaskSummary summary(ResultSet rs, int row) throws SQLException {
        return new CitationTaskSummary(rs.getObject("task_id", UUID.class), rs.getString("title"),
                CitationTaskStatus.valueOf(rs.getString("status")), CitationStage.valueOf(rs.getString("stage")),
                rs.getDouble("progress"), rs.getString("citation_style"), rs.getString("engine_provider"),
                rs.getInt("statement_count"), rs.getInt("selected_count"), time(rs, "created_at"), time(rs, "updated_at"),
                time(rs, "finished_at"), rs.getString("error_message"));
    }

    CitationProgressEvent event(ResultSet rs, int row) throws SQLException {
        return new CitationProgressEvent(rs.getLong("event_id"), CitationStage.valueOf(rs.getString("stage")),
                CitationUpdate.EventType.valueOf(rs.getString("event_type")), rs.getDouble("progress"),
                rs.getString("message"), payload(rs.getString("payload")), time(rs, "occurred_at"));
    }

    String write(Object value) {
        try {
            return value == null ? null : json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode citation task data", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return value == null ? null : json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode citation task data", error);
        }
    }

    private Map<String, Object> payload(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode citation event payload", error);
        }
    }

    private Instant time(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
