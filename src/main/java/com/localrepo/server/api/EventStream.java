package com.localrepo.server.api;

import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.Download;
import com.localrepo.server.artifact.DownloadProgress;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.RequestListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent events for anyone watching the server: {@code download-started}, {@code download-progress} (all active
 * downloads, at most every quarter second), {@code download-completed}, {@code download-failed} and {@code cache-hit}.
 */
public class EventStream implements DownloadTracker.Listener, RequestListener, AutoCloseable {

    private final DownloadTracker tracker;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker;

    public EventStream(DownloadTracker tracker, Duration progressInterval) {
        this.tracker = tracker;
        this.ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("sse-progress").factory());
        ticker.scheduleAtFixedRate(this::publishProgress, progressInterval.toMillis(), progressInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "connected", Map.of("activeDownloads", tracker.active().size()));
        return emitter;
    }

    public void publish(String name, Object data) {
        emitters.forEach(emitter -> send(emitter, name, data));
    }

    @Override
    public void started(Download download) {
        publish("download-started", DownloadProgress.of(download, tracker.now()));
    }

    @Override
    public void finished(Download download) {
        String name = download.state() == Download.State.FAILED ? "download-failed" : "download-completed";
        publish(name, DownloadProgress.of(download, tracker.now()));
    }

    @Override
    public void hit(CachedArtifact artifact) {
        publish("cache-hit", Map.of("path", artifact.path().value(), "size", artifact.meta().size()));
    }

    @Override
    public void miss(ArtifactPath path) {
        // the download events cover it
    }

    private void publishProgress() {
        if (emitters.isEmpty()) {
            return;
        }
        List<DownloadProgress> active = tracker.active();
        if (!active.isEmpty()) {
            publish("download-progress", active);
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(name).data(data));
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }
}
