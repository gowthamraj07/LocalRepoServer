package com.localrepo.server.maintenance;

import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads a list of artifacts into the cache in the background, a few at a time. */
public class Prefetcher {

    private static final Logger log = LoggerFactory.getLogger(Prefetcher.class);
    private static final int PARALLEL = 4;

    private final ArtifactService service;
    private final Clock clock;
    private volatile Report report = new Report(false, null, null, 0, 0, 0, 0, 0, List.of());

    public Prefetcher(ArtifactService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * @param cached  already in the cache
     * @param fetched downloaded now
     * @param missing not found upstream; normal for optional files such as Gradle module metadata
     * @param failed  paths whose download broke
     */
    public record Report(boolean running, Instant startedAt, Instant finishedAt, int total, int done, int cached,
                         int fetched, int missing, List<String> failed) {
    }

    public Report report() {
        return report;
    }

    /** Starts prefetching {@code text} (see {@link PrefetchList}); refuses while another prefetch runs. */
    public synchronized Report start(String text) {
        if (report.running()) {
            throw new IllegalStateException("A prefetch is already running");
        }
        List<ArtifactPath> paths = PrefetchList.parse(text);
        report = new Report(true, clock.instant(), null, paths.size(), 0, 0, 0, 0, List.of());
        Thread.ofVirtual().name("prefetch").start(() -> run(paths));
        return report;
    }

    private void run(List<ArtifactPath> paths) {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger cached = new AtomicInteger();
        AtomicInteger fetched = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        List<String> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        Semaphore slots = new Semaphore(PARALLEL);
        Instant startedAt = report.startedAt();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ArtifactPath path : paths) {
                slots.acquireUninterruptibly();
                executor.submit(() -> {
                    try {
                        switch (service.resolve(path)) {
                            case ArtifactService.Resolution.Cached c -> cached.incrementAndGet();
                            case ArtifactService.Resolution.Missing m -> missing.incrementAndGet();
                            case ArtifactService.Resolution.Downloading d -> {
                                if (d.download().awaitResult().isPresent()) {
                                    fetched.incrementAndGet();
                                } else if (d.download().state() == com.localrepo.server.artifact.Download.State.FAILED) {
                                    failed.add(path.value());
                                } else {
                                    missing.incrementAndGet();
                                }
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        failed.add(path.value());
                    } finally {
                        done.incrementAndGet();
                        slots.release();
                        report = new Report(true, startedAt, null, paths.size(), done.get(), cached.get(),
                                fetched.get(), missing.get(), List.copyOf(failed));
                    }
                });
            }
        } finally {
            report = new Report(false, startedAt, clock.instant(), paths.size(), done.get(), cached.get(),
                    fetched.get(), missing.get(), List.copyOf(failed));
            log.info("Prefetch finished: {}", report);
        }
    }
}
