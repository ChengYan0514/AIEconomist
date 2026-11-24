package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationProgressEvent;
import com.paperflow.client.citation.model.CitationTaskSummary;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class CitationEventHub {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Listener>> listeners = new ConcurrentHashMap<>();
    private final Object[] gates = new Object[64];

    public CitationEventHub() {
        java.util.Arrays.setAll(gates, index -> new Object());
    }

    public <T> T ordered(UUID taskId, Supplier<T> action) {
        synchronized (gates[Math.floorMod(taskId.hashCode(), gates.length)]) {
            return action.get();
        }
    }

    public Runnable subscribe(UUID taskId, Listener listener) {
        listeners.compute(taskId, (id, existing) -> {
            var next = existing == null ? new CopyOnWriteArrayList<Listener>() : existing;
            next.add(listener);
            return next;
        });
        return () -> listeners.computeIfPresent(taskId, (id, existing) -> {
            existing.remove(listener);
            return existing.isEmpty() ? null : existing;
        });
    }

    void publish(UUID taskId, CitationProgressEvent event, CitationTaskSummary complete) {
        List<Listener> current = listeners.get(taskId);
        if (current != null) current.forEach(listener -> listener.send(event, complete));
    }

    @FunctionalInterface
    public interface Listener {
        void send(CitationProgressEvent progress, CitationTaskSummary complete);
    }
}
