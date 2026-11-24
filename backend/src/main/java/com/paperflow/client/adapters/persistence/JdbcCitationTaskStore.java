package com.paperflow.client.adapters.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.client.citation.CitationTaskStore;
import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.model.ClientConflict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class JdbcCitationTaskStore implements CitationTaskStore {
    private static final String ACTIVE = "status IN ('QUEUED', 'RUNNING')";
    private static final String TABLE = "v2_client_citation_task";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CitationTaskRows rows;

    public JdbcCitationTaskStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rows = new CitationTaskRows(json);
    }

    @Override
    public CitationTask.Detail create(String username, CitationTask task, CitationUpdate event, int limit) {
        return tx.execute(ignored -> {
            jdbc.queryForObject("SELECT username FROM v2_admin WHERE username = ? FOR UPDATE", String.class, username);
            long active = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE username = ? AND " + ACTIVE,
                    Long.class, username);
            if (active >= limit) throw new ClientConflict("CITATION_CONFLICT", "已达到活跃引文标注任务上限");
            jdbc.update("""
                    INSERT INTO v2_client_citation_task
                    (task_id, username, title, status, stage, progress, original_text, citation_style, retrieval_policy,
                     engine_provider, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """, task.taskId(), username, task.title(), task.state().status().name(), task.state().stage().name(),
                    task.state().progress(), task.originalText(), task.style().id(), rows.write(task.retrievalPolicy()),
                    task.engineProvider(), time(task.createdAt()), time(task.state().updatedAt()));
            return new CitationTask.Detail(task, List.of(append(task.taskId(), event)));
        });
    }

    @Override
    public Page list(String username, CitationTaskStatus status, int page, int size) {
        String filter = status == null ? " WHERE username = ?" : " WHERE username = ? AND status = ?";
        Object[] args = status == null ? new Object[]{username} : new Object[]{username, status.name()};
        var params = java.util.Arrays.copyOf(args, args.length + 2);
        params[args.length] = size;
        params[args.length + 1] = (long) page * size;
        long total = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + filter, Long.class, args);
        var items = jdbc.query("""
                SELECT task_id, title, status, stage, progress, citation_style, engine_provider,
                       created_at, updated_at, finished_at, error_message,
                       coalesce(jsonb_array_length(result_payload->'statements'), 0) AS statement_count,
                       (SELECT coalesce(sum(jsonb_array_length(value)), 0)
                        FROM jsonb_each(result_payload->'selectedReferenceIds')) AS selected_count
                FROM v2_client_citation_task
                """ + filter + " ORDER BY created_at DESC, task_id DESC LIMIT ? OFFSET ?", rows::summary, params);
        return new Page(items, page, size, total);
    }

    @Override
    public <T> T locked(UUID id, String username, Function<CitationTask, T> action) {
        return tx.execute(ignored -> {
            String lookup = "SELECT * FROM " + TABLE + " WHERE task_id = ?";
            var matches = username == null ? jdbc.query(lookup + " FOR UPDATE", rows::task, id)
                    : jdbc.query(lookup + " AND username = ? FOR UPDATE", rows::task, id, username);
            return action.apply(matches.isEmpty() ? null : matches.get(0));
        });
    }

    @Override
    public List<UUID> activeTaskIds() {
        return jdbc.queryForList("SELECT task_id FROM " + TABLE + " WHERE " + ACTIVE, UUID.class);
    }

    @Override
    public List<CitationProgressEvent> events(UUID id) {
        return jdbc.query("SELECT * FROM v2_client_citation_event WHERE task_id = ? ORDER BY event_id", rows::event, id);
    }

    @Override
    public CitationProgressEvent append(UUID id, CitationUpdate event) {
        return jdbc.queryForObject("""
                INSERT INTO v2_client_citation_event (task_id, stage, event_type, progress, message, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING *
                """, rows::event, id, event.stage().name(), event.eventType().name(), event.progress(), event.message(),
                rows.write(event.payload()));
    }

    @Override
    public void save(CitationTask task) {
        var state = task.state();
        jdbc.update("""
                UPDATE v2_client_citation_task SET status = ?, stage = ?, progress = ?, engine_run_id = ?,
                    error_code = ?, error_message = ?, updated_at = ?, finished_at = ?, citation_style = ?,
                    result_payload = ?::jsonb, annotated_text = ?, bibliography = ?
                WHERE task_id = ?
                """, state.status().name(), state.stage().name(), state.progress(), state.engineRunId(), state.errorCode(),
                state.errorMessage(), time(state.updatedAt()), time(state.finishedAt()), task.style().id(),
                rows.write(task.result()), task.annotatedText(), task.bibliography(), task.taskId());
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE task_id = ?", id);
    }

    private OffsetDateTime time(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
