package com.paperflow.client.adapters.web;

import com.paperflow.client.citation.CitationEventHub;
import com.paperflow.client.citation.CitationTaskService;
import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationTaskSummary;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

final class CitationTaskSse {
    private final CitationTaskService tasks;
    private final CitationEventHub events;

    CitationTaskSse(CitationTaskService tasks, CitationEventHub events) {
        this.tasks = tasks;
        this.events = events;
    }

    SseEmitter open(String username, UUID id) {
        return events.ordered(id, () -> {
            var detail = tasks.detail(username, id);
            var stream = new Stream();
            if (detail.task().state().status().active()) {
                stream.unsubscribe = events.subscribe(id, stream::send);
            }
            detail.events().forEach(event -> stream.send(event, null));
            if (!detail.task().state().status().active()) stream.send(null, detail.task().summary());
            return stream.emitter;
        });
    }

    private static final class Stream {
        private final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        private long lastEventId;
        private boolean closed;
        private Runnable unsubscribe = () -> {};

        private Stream() {
            emitter.onCompletion(() -> unsubscribe.run());
            emitter.onTimeout(() -> unsubscribe.run());
            emitter.onError(error -> unsubscribe.run());
        }

        private void send(CitationProgressEvent event, CitationTaskSummary summary) {
            if (closed) return;
            try {
                if (event != null && event.eventId() > lastEventId) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.eventId())).name("progress")
                            .data(event, MediaType.APPLICATION_JSON));
                    lastEventId = event.eventId();
                }
                if (summary != null) {
                    emitter.send(SseEmitter.event().name("complete").data(summary, MediaType.APPLICATION_JSON));
                    close();
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException disconnected) {
                close();
            }
        }

        private void close() {
            closed = true;
            unsubscribe.run();
        }
    }
}
