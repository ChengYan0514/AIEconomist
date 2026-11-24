package com.paperflow.systemstatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SystemStatus implements AutoCloseable {
    public static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);

    public enum Dependency { POSTGRESQL, MINERU, CURRENT_STORAGE, LEGACY_STORAGE, OPENALEX_SNAPSHOT, CAUSAL_DATABASE, CAUSAL_ENGINE }
    public enum State { HEALTHY, UNAVAILABLE, NOT_CHECKED }

    @FunctionalInterface
    public interface Check {
        void run() throws Exception;
    }

    public record Probe(Dependency dependency, Check check, String success, String failure, String timeout, boolean enabled, String disabledMessage) {
        public Probe(Dependency dependency, Check check, String success, String failure, String timeout) {
            this(dependency, check, success, failure, timeout, true, "");
        }
    }
    public record Result(Dependency dependency, State state, Instant checkedAt, String message) {}
    public record Configuration(
            String version, long maxOriginalBytes, long maxZipBytes, int maxJobTargets,
            boolean causalEnabled, String causalDefaultMode, int causalMaxBatchTargets
    ) {}
    public record Report(List<Result> checks, Configuration configuration) {}

    private final ExecutorService executor;
    private final List<ProbeRun> probes;
    private final Configuration configuration;

    public SystemStatus(List<Probe> probes, Configuration configuration) {
        executor = Executors.newFixedThreadPool(probes.size(), task -> {
            var thread = new Thread(task, "system-status-probe");
            thread.setDaemon(true);
            return thread;
        });
        this.probes = probes.stream().map(ProbeRun::new).toList();
        this.configuration = configuration;
    }

    public Report refresh() {
        var pending = probes.stream().map(ProbeRun::start).toList();
        return new Report(pending.stream().map(CompletableFuture::join).toList(), configuration);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private final class ProbeRun {
        private final Probe probe;
        private CompletableFuture<Result> inFlight;

        private ProbeRun(Probe probe) {
            this.probe = probe;
        }

        private synchronized CompletableFuture<Result> start() {
            // A stalled dependency keeps its slot until the underlying operation returns.
            if (inFlight != null) return inFlight;
            var completion = new CompletableFuture<Result>();
            inFlight = completion.orTimeout(CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> result(State.UNAVAILABLE, probe.timeout()));
            executor.execute(() -> run(completion));
            return inFlight;
        }

        private void run(CompletableFuture<Result> completion) {
            Result result = inspect();
            synchronized (this) {
                inFlight = null;
                completion.complete(result);
            }
        }

        private Result inspect() {
            if (!probe.enabled()) {
                return new Result(probe.dependency(), State.NOT_CHECKED, null, probe.disabledMessage());
            }
            try {
                probe.check().run();
                return result(State.HEALTHY, probe.success());
            } catch (TimeoutException timeout) {
                return result(State.UNAVAILABLE, probe.timeout());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(probe.dependency(), State.NOT_CHECKED, null, "检查已中断");
            } catch (Exception failure) {
                return result(State.UNAVAILABLE, probe.failure());
            }
        }

        private Result result(State state, String message) {
            return new Result(probe.dependency(), state, Instant.now(), message);
        }
    }
}
