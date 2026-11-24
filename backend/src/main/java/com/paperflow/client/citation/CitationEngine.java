package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStyle;
import com.paperflow.client.citation.model.CitationUpdate;
import com.paperflow.client.citation.model.RetrievalPolicy;

import java.util.UUID;

public interface CitationEngine {
    String providerId();

    boolean isHealthy();

    CitationResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled)
            throws InterruptedException;

    record EngineRequest(UUID runId, String text, CitationStyle citationStyle, RetrievalPolicy retrievalPolicy) {}

    @FunctionalInterface
    interface ProgressSink {
        void update(CitationUpdate update);

        default void runStarted(String engineRunId) {}
    }

    @FunctionalInterface
    interface CancellationSignal {
        boolean cancelled();

        default void check() throws InterruptedException {
            if (cancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }
    }

    final class EngineFailure extends RuntimeException {
        private final String code;

        public EngineFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
