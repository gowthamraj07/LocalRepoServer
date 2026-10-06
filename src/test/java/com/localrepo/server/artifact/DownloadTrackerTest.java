package com.localrepo.server.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTrackerTest {

    private final NegativeCacheTest.MutableClock clock = new NegativeCacheTest.MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final DownloadTracker tracker = new DownloadTracker(clock);

    @Test
    void reportsProgressOfActiveDownloads() {
        Download download = new Download(ArtifactPath.of("g/a/1/a-1.aar"), clock);
        tracker.started(download);
        download.streaming(new Origin("https://up/g/a/1/a-1.aar", null, null), 4000, Path.of("ignored"), () -> { });
        clock.advance(Duration.ofSeconds(2));
        download.progress(1000);

        DownloadProgress progress = tracker.active().getFirst();

        assertEquals("g/a/1/a-1.aar", progress.path());
        assertEquals("https://up/g/a/1/a-1.aar", progress.upstreamUrl());
        assertEquals(Download.State.STREAMING, progress.state());
        assertEquals(1000, progress.bytes());
        assertEquals(4000, progress.totalBytes());
        assertEquals(500, progress.bytesPerSecond());
    }

    @Test
    void movesFinishedDownloadsToRecentNewestFirst() {
        Download first = new Download(ArtifactPath.of("g/a/1/a-1.pom"), clock);
        Download second = new Download(ArtifactPath.of("g/a/1/a-1.jar"), clock);
        tracker.started(first);
        tracker.started(second);
        first.notFound();
        tracker.finished(first);
        second.notFound();
        tracker.finished(second);

        assertTrue(tracker.active().isEmpty());
        assertEquals(List.of("g/a/1/a-1.jar", "g/a/1/a-1.pom"),
                tracker.recent().stream().map(DownloadProgress::path).toList());
    }

    @Test
    void keepsABoundedHistory() {
        for (int i = 0; i < DownloadTracker.RECENT_LIMIT + 10; i++) {
            Download download = new Download(ArtifactPath.of("g/a/" + i + "/a.pom"), clock);
            tracker.started(download);
            tracker.finished(download);
        }

        assertEquals(DownloadTracker.RECENT_LIMIT, tracker.recent().size());
    }
}
