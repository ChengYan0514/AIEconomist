package com.paperflow.causal.extraction;

import com.paperflow.causal.extraction.CausalExtractionModels.RunEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CausalEventHub {
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID batchId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        var list = subscribers.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> remove(batchId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("batchId", batchId.toString())));
        } catch (IOException error) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(RunEvent event) {
        var list = subscribers.get(event.batchId());
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("run").data(event));
            } catch (IOException error) {
                remove(event.batchId(), emitter);
            }
        }
    }

    private void remove(UUID batchId, SseEmitter emitter) {
        var list = subscribers.get(batchId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(batchId, list);
        }
    }
}
