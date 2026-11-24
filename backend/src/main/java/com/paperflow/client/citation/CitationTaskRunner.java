package com.paperflow.client.citation;

import com.paperflow.client.citation.CitationEngine.CancellationSignal;
import com.paperflow.client.citation.CitationEngine.EngineFailure;
import com.paperflow.client.citation.CitationEngine.EngineRequest;
import com.paperflow.client.citation.CitationEngine.ProgressSink;
import com.paperflow.client.citation.model.CitationTask;
import com.paperflow.client.citation.model.CitationUpdate;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.paperflow.client.citation.model.CitationStage.ENRICH_METADATA;
import static com.paperflow.client.citation.model.CitationStage.FORMAT_OUTPUT;
import static com.paperflow.client.citation.model.CitationUpdate.EventType.STAGE_STARTED;

public final class CitationTaskRunner implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(CitationTaskRunner.class.getName());
    private final CitationTaskLifecycle lifecycle;
    private final CitationEngine engine;
    private final LocalMetadataEnricher enricher;
    private final CitationFormatter formatter;
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<UUID, FutureTask<Void>> inFlight = new ConcurrentHashMap<>();

    public CitationTaskRunner(CitationTaskLifecycle lifecycle, CitationEngine engine, LocalMetadataEnricher enricher,
                              CitationFormatter formatter, int concurrency) {
        this.lifecycle = lifecycle;
        this.engine = engine;
        this.enricher = enricher;
        this.formatter = formatter;
        this.pool = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256));
    }

    void schedule(UUID id) {
        var task = new FutureTask<Void>(() -> run(id), null) {
            @Override
            protected void done() {
                inFlight.remove(id, this);
            }
        };
        inFlight.put(id, task);
        try {
            pool.execute(task);
        } catch (RejectedExecutionException error) {
            task.cancel(false);
            lifecycle.fail(id, "CITATION_QUEUE_FULL", "引文标注队列已满，请稍后重试");
        }
    }

    void cancel(UUID id) {
        var task = inFlight.get(id);
        if (task != null) {
            task.cancel(true);
            pool.remove(task);
        }
    }

    private void run(UUID id) {
        CancellationSignal cancelled = Thread.currentThread()::isInterrupted;
        try {
            var task = lifecycle.start(id);
            if (task != null) execute(task, cancelled);
        } catch (InterruptedException error) {
            try {
                lifecycle.fail(id, "INTERRUPTED", "引文标注运行已中断");
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (EngineFailure error) {
            lifecycle.fail(id, error.code(), error.getMessage());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.ERROR, "Citation task " + id + " failed", error);
            lifecycle.fail(id, "CITATION_FAILED", "引文标注失败");
        }
    }

    private void execute(CitationTask task, CancellationSignal cancelled) throws InterruptedException {
        var id = task.taskId();
        var request = new EngineRequest(id, task.originalText(), task.style(), task.retrievalPolicy());
        var raw = engine.execute(request, sink(id), cancelled);
        cancelled.check();
        lifecycle.progress(id, new CitationUpdate(ENRICH_METADATA, STAGE_STARTED, 0.85, "补全本地书目信息"));
        var result = enricher.enrich(raw);
        cancelled.check();
        lifecycle.progress(id, new CitationUpdate(FORMAT_OUTPUT, STAGE_STARTED, 0.95, "重排引文与参考文献"));
        var output = formatter.format(task.originalText(), task.style(), result);
        lifecycle.succeed(id, current -> current.withOutput(current.style(), result, output.annotatedText(),
                output.bibliography()));
    }

    private ProgressSink sink(UUID id) {
        return new ProgressSink() {
            @Override
            public void update(CitationUpdate update) {
                lifecycle.progress(id, update);
            }

            @Override
            public void runStarted(String engineRunId) {
                lifecycle.engineStarted(id, engineRunId);
            }
        };
    }

    @Override
    public void close() {
        pool.shutdownNow();
        inFlight.values().forEach(task -> task.cancel(true));
    }
}
