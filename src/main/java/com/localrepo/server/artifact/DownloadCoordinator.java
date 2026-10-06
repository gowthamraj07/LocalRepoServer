package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs at most one {@link Download} per path, each on its own virtual thread so it outlives the request that started
 * it, and aborts downloads whose upstream goes quiet for longer than the idle timeout.
 */
public class DownloadCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DownloadCoordinator.class);

    private final Clock clock;
    private final Duration idleTimeout;
    private final DownloadTracker tracker;
    private final Map<String, Download> inFlight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;

    public DownloadCoordinator(Clock clock, Duration idleTimeout, DownloadTracker tracker) {
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.tracker = tracker;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("download-watchdog").factory());
        long period = Math.max(50, Math.min(1000, idleTimeout.toMillis() / 3));
        watchdog.scheduleAtFixedRate(this::abortIdleDownloads, period, period, TimeUnit.MILLISECONDS);
    }

    /** Returns the download already running under {@code key}, or starts {@code fetch} in a new one. */
    public Download join(String key, ArtifactPath path, Consumer<Download> fetch) {
        boolean[] created = {false};
        Download download = inFlight.computeIfAbsent(key, k -> {
            created[0] = true;
            return new Download(path, clock);
        });
        if (created[0]) {
            tracker.started(download);
            Thread.ofVirtual().name("download-" + path.fileName()).start(() -> run(key, download, fetch));
        }
        return download;
    }

    private void run(String key, Download download, Consumer<Download> fetch) {
        try {
            fetch.accept(download);
        } catch (RuntimeException e) {
            log.error("Download of {} crashed", download.path().value(), e);
            download.failed(new java.io.IOException(e));
        } finally {
            inFlight.remove(key, download);
            tracker.finished(download);
        }
    }

    private void abortIdleDownloads() {
        for (Download download : inFlight.values()) {
            if (download.state() == Download.State.STREAMING
                    && download.lastActivity().plus(idleTimeout).isBefore(clock.instant())) {
                log.warn("No data for {} from upstream in {}, aborting", download.path().value(), idleTimeout);
                download.abortUpstream();
            }
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }
}
