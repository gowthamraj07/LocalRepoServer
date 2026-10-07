package com.localrepo.server.api;

import com.localrepo.server.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AccessStatsTest {

    @TempDir
    Path cacheDir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));

    @Test
    void countsHitsMissesAndBytesPerFileAndOverall() {
        AccessStats stats = new AccessStats(cacheDir, clock);
        Path jar = cacheDir.resolve("central/junit/junit/4.13.2/junit-4.13.2.jar");

        stats.miss();
        stats.downloaded(1000);
        stats.hit(jar, 1000);
        clock.advance(Duration.ofMinutes(1));
        stats.hit(jar, 1000);

        AccessStats.Totals totals = stats.totals();
        assertEquals(2, totals.hits());
        assertEquals(1, totals.misses());
        assertEquals(2000, totals.bytesServedFromCache());
        assertEquals(1000, totals.bytesDownloaded());
        AccessStats.FileAccess access = stats.access("central/junit/junit/4.13.2/junit-4.13.2.jar");
        assertEquals(2, access.hits());
        assertEquals(clock.instant(), access.lastAccess());
    }

    @Test
    void survivesARestart() throws Exception {
        AccessStats stats = new AccessStats(cacheDir, clock);
        stats.hit(cacheDir.resolve("central/a/b/1/b-1.jar"), 10);
        stats.miss();
        stats.flush();

        AccessStats reloaded = new AccessStats(cacheDir, clock);

        assertEquals(1, reloaded.totals().hits());
        assertEquals(1, reloaded.totals().misses());
        assertEquals(1, reloaded.access("central/a/b/1/b-1.jar").hits());
    }

    @Test
    void doesNotRecreateAMissingCacheDirectory() {
        Path gone = cacheDir.resolve("gone");
        AccessStats stats = new AccessStats(gone, clock);
        stats.miss();

        assertThrows(java.io.IOException.class, stats::flush);

        assertFalse(java.nio.file.Files.exists(gone));
    }

    @Test
    void addsToTheSavedIndexWhenTheCacheDirectoryOnlyAppearsLater() throws Exception {
        AccessStats before = new AccessStats(cacheDir, clock);
        before.hit(cacheDir.resolve("central/a/b/1/b-1.jar"), 10);
        before.flush();
        Path disconnected = cacheDir.resolveSibling(cacheDir.getFileName() + "-away");
        java.nio.file.Files.move(cacheDir, disconnected);

        AccessStats stats = new AccessStats(cacheDir, clock);
        stats.hit(cacheDir.resolve("central/a/b/1/b-1.jar"), 10);
        java.nio.file.Files.move(disconnected, cacheDir);
        stats.flush();

        AccessStats reloaded = new AccessStats(cacheDir, clock);
        assertEquals(2, reloaded.totals().hits());
        assertEquals(2, reloaded.access("central/a/b/1/b-1.jar").hits());
    }

    @Test
    void forgetsDeletedFiles() {
        AccessStats stats = new AccessStats(cacheDir, clock);
        stats.hit(cacheDir.resolve("central/a/b/1/b-1.jar"), 10);

        stats.forget("central/a/b/1/b-1.jar");

        assertEquals(0, stats.access("central/a/b/1/b-1.jar").hits());
    }
}
