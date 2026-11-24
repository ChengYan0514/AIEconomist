package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.model.ClientConflict;
import com.paperflow.client.model.ClientNotFound;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import static com.paperflow.client.citation.model.CitationTaskStatus.QUEUED;
import static com.paperflow.client.citation.model.CitationTaskStatus.RUNNING;
import static com.paperflow.client.citation.model.CitationTaskStatus.SUCCEEDED;
import static com.paperflow.client.citation.model.CitationUpdate.EventType.*;

public final class CitationTaskLifecycle {
    private final CitationTaskStore store;
    private final CitationEventHub events;

    public CitationTaskLifecycle(CitationTaskStore store, CitationEventHub events) {
        this.store = store;
        this.events = events;
    }

    CitationTask start(UUID id) {
        var update = new CitationUpdate(CitationStage.QUEUED, STATUS_CHANGED, 0, "引文标注开始");
        var changed = change(id, null, task -> task.state().status() == QUEUED
                ? persist(task, RUNNING, update, null, null) : null);
        return changed == null ? null : changed.task();
    }

    void engineStarted(UUID id, String engineRunId) {
        store.locked(id, null, task -> {
            if (task != null && task.state().status() == RUNNING) {
                var state = task.state();
                store.save(task.withState(new CitationTask.State(state.status(), state.stage(), state.progress(),
                        engineRunId, null, null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), null)));
            }
            return null;
        });
    }

    void progress(UUID id, CitationUpdate update) {
        change(id, null, task -> {
            var state = task.state();
            if (state.status() != RUNNING || update.stage().terminal()) return null;
            var stage = update.stage().ordinal() >= state.stage().ordinal() ? update.stage() : state.stage();
            double progress = Double.isFinite(update.progress()) ? Math.min(1, update.progress()) : 0;
            var normalized = new CitationUpdate(stage, update.eventType(), Math.max(progress, state.progress()),
                    update.message(), update.payload());
            return persist(task, RUNNING, normalized, null, null);
        });
    }

    void succeed(UUID id, Function<CitationTask, CitationTask> render) {
        change(id, null, task -> task.state().status() == RUNNING
                ? persist(render.apply(task), SUCCEEDED, new CitationUpdate(CitationStage.COMPLETED,
                        COMPLETED, 1, "引文标注已完成"), null, null) : null);
    }

    void fail(UUID id, String code, String message) {
        change(id, null, task -> task.state().status().active()
                ? persist(task, CitationTaskStatus.FAILED, new CitationUpdate(CitationStage.FAILED,
                        FAILED, task.state().progress(), message), code, message) : null);
    }

    public void recover() {
        for (UUID id : store.activeTaskIds()) fail(id, "INTERRUPTED", "服务重启前引文标注尚未完成");
    }

    CitationTask.Detail cancel(UUID id, String username) {
        return events.ordered(id, () -> {
            var changed = change(id, username, task -> {
                if (!task.state().status().active()) {
                    throw new ClientConflict("CITATION_TASK_TERMINAL", "引文标注任务已结束");
                }
                return persist(task, CitationTaskStatus.CANCELLED, new CitationUpdate(CitationStage.CANCELLED,
                        CANCELLED, task.state().progress(), "引文标注已取消"), null, null);
            });
            return new CitationTask.Detail(changed.task(), store.events(id));
        });
    }

    private Change change(UUID id, String username, Function<CitationTask, Change> transition) {
        return events.ordered(id, () -> {
            var change = store.locked(id, username, task -> {
                if (task != null) return transition.apply(task);
                if (username != null) throw notFound();
                return null;
            });
            if (change != null) {
                var task = change.task();
                events.publish(id, change.event(), task.state().status().active() ? null : task.summary());
            }
            return change;
        });
    }

    private Change persist(CitationTask task, CitationTaskStatus status, CitationUpdate event, String code, String message) {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var changed = task.withState(new CitationTask.State(status, event.stage(), event.progress(),
                task.state().engineRunId(), code, message, now, status.active() ? null : now));
        store.save(changed);
        return new Change(changed, store.append(task.taskId(), event));
    }

    static ClientNotFound notFound() {
        return new ClientNotFound("CITATION_TASK_NOT_FOUND", "找不到引文标注任务");
    }

    private record Change(CitationTask task, CitationProgressEvent event) {}
}
