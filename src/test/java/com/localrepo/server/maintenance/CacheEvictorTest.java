package com.localrepo.server.maintenance;

import com.localrepo.server.MutableClock;
import com.localrepo.server.api.AccessStats;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.DownloadCoordinator;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.FreshnessPolicy;
import com.localrepo.server.artifact.NegativeCache;
import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.artifact.Origin;
import com.localrepo.server.artifact.Repository;
import com.localrepo.server.artifact.UpstreamClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheEvictorTest {

    @TempDir
    Path cacheDir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private ArtifactService service;
    private AccessStats stats;
    private ArtifactStore central;

    @BeforeEach
    void setUp() {
        central = new ArtifactStore(cacheDir.resolve("central"), clock);
        Repository repository = new Repository("central", "http://127.0.0.1:9", List.of(), List.of(),
                Repository.Credentials.NONE, central);
        service = new ArtifactService(List.of(repository),
                new UpstreamClient(HttpClient.newHttpClient(), Duration.ofSeconds(1), Duration.ZERO),
                new NegativeCache(Duration.ZERO, clock),
                new DownloadCoordinator(clock, Duration.ofSeconds(1), new DownloadTracker(clock)),
                new FreshnessPolicy(Duration.ofHours(24), clock), new OfflineMode(true));
        stats = new AccessStats(cacheDir, clock);
    }

    @Test
    void doesNothingWithoutALimit() throws IOException {
        cache("a/a/1/a-1.jar", 100);

        CacheEvictor.Result result = new CacheEvictor(service, stats, 0, List.of()).evict();

        assertEquals(0, result.deleted().size());
    }

    @Test
    void removesTheLeastRecentlyUsedFilesUntilWellUnderTheLimit() throws IOException {
        cache("a/a/1/a-1.jar", 400);
        clock.advance(Duration.ofMinutes(1));
        cache("b/b/1/b-1.jar", 400);
        clock.advance(Duration.ofMinutes(1));
        cache("c/c/1/c-1.jar", 400);
        clock.advance(Duration.ofMinutes(1));
        stats.hit(cacheDir.resolve("central/a/a/1/a-1.jar"), 400);

        CacheEvictor.Result result = new CacheEvictor(service, stats, 1000, List.of()).evict();

        assertEquals(List.of("central/b/b/1/b-1.jar"), result.deleted());
        assertEquals(1200, result.sizeBefore());
        assertEquals(800, result.sizeAfter());
        assertTrue(central.find(ArtifactPath.of("a/a/1/a-1.jar")).isPresent());
        assertTrue(central.find(ArtifactPath.of("b/b/1/b-1.jar")).isEmpty());
    }

    @Test
    void neverEvictsPinnedPaths() throws IOException {
        cache("androidx/core/1/core-1.aar", 600);
        clock.advance(Duration.ofMinutes(1));
        cache("b/b/1/b-1.jar", 600);

        CacheEvictor.Result result = new CacheEvictor(service, stats, 1000, List.of("androidx/**")).evict();

        assertEquals(List.of("central/b/b/1/b-1.jar"), result.deleted());
        assertTrue(central.find(ArtifactPath.of("androidx/core/1/core-1.aar")).isPresent());
    }

    @Test
    void purgesFilesUnusedForAWhile() throws IOException {
        cache("old/old/1/old-1.jar", 10);
        clock.advance(Duration.ofDays(40));
        cache("new/new/1/new-1.jar", 10);

        List<String> purged = new CachePurger(service, stats, clock).purge(null, Duration.ofDays(30));

        assertEquals(List.of("central/old/old/1/old-1.jar"), purged);
    }

    @Test
    void purgesEverythingBelowAPathAcrossRepositories() throws IOException {
        cache("com/example/a/1/a-1.jar", 10);
        cache("com/example/b/1/b-1.jar", 10);
        cache("com/other/c/1/c-1.jar", 10);

        List<String> purged = new CachePurger(service, stats, clock).purge("com/example", null);

        assertEquals(List.of("central/com/example/a/1/a-1.jar", "central/com/example/b/1/b-1.jar"), purged);
        assertTrue(central.find(ArtifactPath.of("com/other/c/1/c-1.jar")).isPresent());
    }

    @Test
    void refusesToPurgeEverythingByAccident() {
        assertThrows(IllegalArgumentException.class, () -> new CachePurger(service, stats, clock).purge(null, null));
    }

    private void cache(String path, int size) throws IOException {
        central.save(ArtifactPath.of(path), new ByteArrayInputStream(new byte[size]), new Origin("http://x", null, null));
    }
}
