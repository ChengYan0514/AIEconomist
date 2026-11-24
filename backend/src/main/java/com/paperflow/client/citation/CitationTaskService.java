package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationStage;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationSubmission;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationTaskStatus;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.model.ClientConflict;
import com.paperflow.client.model.ClientInvalid;
import com.paperflow.client.model.ClientUnavailable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CitationTaskService {
    public enum ExportFormat { TXT, MD, BIBTEX }

    private final CitationTaskStore store;
    private final CitationTaskLifecycle lifecycle;
    private final CitationTaskRunner runner;
    private final CitationEngine engine;
    private final CitationEventHub events;
    private final CitationTaskReview review;
    private final int activeLimit;

    public CitationTaskService(CitationTaskStore store, CitationTaskLifecycle lifecycle, CitationTaskRunner runner,
                               CitationEngine engine, CitationEventHub events, CitationFormatter formatter,
                               BibtexExporter exporter, int activeLimit) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.runner = runner;
        this.engine = engine;
        this.events = events;
        this.review = new CitationTaskReview(formatter, exporter);
        this.activeLimit = activeLimit;
    }

    public CitationTask.Detail create(String user, CitationSubmission submission) {
        if (!engine.isHealthy()) {
            throw new ClientUnavailable("CITATION_ENGINE_UNAVAILABLE", "参考文献自动标注引擎暂不可用");
        }
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var state = new CitationTask.State(CitationTaskStatus.QUEUED, CitationStage.QUEUED, 0,
                null, null, null, now, null);
        var task = new CitationTask(UUID.randomUUID(), submission.title(), submission.text(), submission.style(),
                submission.retrievalPolicy(), engine.providerId(), now, state, null, null, null);
        var snapshot = store.create(user, task, new CitationUpdate(CitationStage.QUEUED,
                CitationUpdate.EventType.STATUS_CHANGED, 0, "引文标注已排队"), activeLimit);
        runner.schedule(task.taskId());
        return snapshot;
    }

    public CitationTaskStore.Page list(String user, CitationTaskStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new ClientInvalid("引文标注任务分页参数无效");
        return store.list(user, status, page, size);
    }

    public CitationTask.Detail detail(String user, UUID id) {
        return store.locked(id, user, task -> new CitationTask.Detail(owned(task), store.events(id)));
    }

    public CitationTask.Detail cancel(String user, UUID id) {
        var snapshot = lifecycle.cancel(id, user);
        runner.cancel(id);
        return snapshot;
    }

    public CitationTask.Detail review(String user, UUID id, CitationStyle style, Map<String, List<String>> selection) {
        return store.locked(id, user, task -> {
            var reviewed = review.review(owned(task), style, selection);
            store.save(reviewed);
            return new CitationTask.Detail(reviewed, store.events(id));
        });
    }

    public String export(String user, UUID id, ExportFormat format, CitationStyle override) {
        var task = store.locked(id, user, CitationTaskService::owned);
        return review.export(task, format, override);
    }

    public void delete(String user, UUID id) {
        events.ordered(id, () -> store.locked(id, user, task -> {
            if (owned(task).state().status().active()) {
                throw new ClientConflict("CITATION_TASK_ACTIVE", "请先取消活跃的引文标注任务");
            }
            store.delete(id);
            return null;
        }));
    }

    private static CitationTask owned(CitationTask task) {
        if (task == null) throw CitationTaskLifecycle.notFound();
        return task;
    }
}
