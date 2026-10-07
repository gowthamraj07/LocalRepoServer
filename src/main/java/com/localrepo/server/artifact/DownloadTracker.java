package com.localrepo.server.artifact;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Live and recently finished downloads, for monitoring. */
public class DownloadTracker {

    static final int RECENT_LIMIT = 100;

    private final Clock clock;
    private final Set<Download> active = ConcurrentHashMap.newKeySet();
    private final Deque<Download> recent = new ArrayDeque<>();
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public interface Listener {
        default void started(Download download) {
        }

        default void finished(Download download) {
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public DownloadTracker(Clock clock) {
        this.clock = clock;
    }

    void started(Download download) {
        active.add(download);
        listeners.forEach(l -> l.started(download));
    }

    void finished(Download download) {
        active.remove(download);
        synchronized (recent) {
            recent.addFirst(download);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeLast();
            }
        }
        listeners.forEach(l -> l.finished(download));
    }

    public Instant now() {
        return clock.instant();
    }

    public List<DownloadProgress> active() {
        return active.stream()
                .map(d -> DownloadProgress.of(d, clock.instant()))
                .sorted(java.util.Comparator.comparing(DownloadProgress::startedAt))
                .toList();
    }

    /** Finished downloads, newest first. */
    public List<DownloadProgress> recent() {
        synchronized (recent) {
            return recent.stream().map(d -> DownloadProgress.of(d, clock.instant())).toList();
        }
    }
}
