package com.localrepo.server.api;

import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.CacheLocation;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.DownloadProgress;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.InvalidArtifactPathException;
import com.localrepo.server.artifact.Repository;
import com.localrepo.server.setup.SetupController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** JSON views of the cache for the UI and scripts. Changes need the {@code X-LocalRepo-Action} header. */
@RestController
public class ApiController {

    private final ArtifactService service;
    private final AccessStats stats;
    private final CacheLocation cacheLocation;
    private final DownloadTracker downloads;
    private final EventStream events;

    public ApiController(ArtifactService service, AccessStats stats, DownloadTracker downloads, EventStream events,
                         CacheLocation cacheLocation) {
        this.cacheLocation = cacheLocation;
        this.service = service;
        this.stats = stats;
        this.downloads = downloads;
        this.events = events;
    }

    public record ArtifactView(String repository, String path, String group, String artifact, String version,
                               String file, long size, long hits, Instant lastAccess, Instant fetchedAt,
                               String upstreamUrl, String checksumVerified) {
    }

    public record Page<T>(List<T> items, int total, int page, int size) {
    }

    public record Stats(long hits, long misses, double hitRate, long bytesServedFromCache, long bytesDownloaded,
                        long diskUsage, long artifactCount, int activeDownloads, String cacheDir,
                        boolean cacheAvailable, long freeSpace) {
    }

    public record Downloads(List<DownloadProgress> active, List<DownloadProgress> recent) {
    }

    /** Cached files, sorted by repository and path; {@code q} matches anywhere in the path, ignoring case. */
    @GetMapping("/api/artifacts")
    public Page<ArtifactView> artifacts(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String repository,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "100") int size) {
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT);
        List<ArtifactView> all = new ArrayList<>();
        for (Map.Entry<Repository, List<CachedArtifact>> entry : service.list().entrySet()) {
            String name = entry.getKey().name();
            if (repository != null && !repository.equals(name)) {
                continue;
            }
            for (CachedArtifact artifact : entry.getValue()) {
                if (artifact.path().value().toLowerCase(Locale.ROOT).contains(needle)) {
                    all.add(view(name, artifact));
                }
            }
        }
        all.sort(Comparator.comparing(ArtifactView::repository).thenComparing(ArtifactView::path));
        int pageSize = Math.max(1, Math.min(size, 10_000));
        int from = Math.min(all.size(), Math.max(0, page) * pageSize);
        return new Page<>(all.subList(from, Math.min(all.size(), from + pageSize)), all.size(), page, pageSize);
    }

    /** Deletes the file at {@code path}, or everything below it, from one repository's cache. */
    @DeleteMapping("/api/artifacts")
    public Map<String, Integer> delete(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                                       @RequestParam String repository, @RequestParam String path) throws IOException {
        requireAction(action);
        Repository target = repository(repository);
        String prefix = ArtifactPath.of(path).value();
        int deleted = 0;
        for (CachedArtifact artifact : target.store().list()) {
            String value = artifact.path().value();
            if (value.equals(prefix) || value.startsWith(prefix + "/")) {
                target.store().delete(artifact.path());
                stats.forget(target.name() + "/" + value);
                deleted++;
            }
        }
        return Map.of("deleted", deleted);
    }

    /** Drops one cached file and downloads it again from its repository. */
    @PostMapping("/api/artifacts/refetch")
    public ResponseEntity<Map<String, String>> refetch(
            @RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
            @RequestParam String repository, @RequestParam String path) throws IOException {
        requireAction(action);
        Repository target = repository(repository);
        ArtifactPath artifactPath = ArtifactPath.of(path);
        target.store().delete(artifactPath);
        return switch (service.resolve(target.name(), artifactPath)) {
            case ArtifactService.Resolution.Missing missing -> ResponseEntity.notFound().build();
            default -> ResponseEntity.accepted().body(Map.of("repository", target.name(), "path", artifactPath.value()));
        };
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        AccessStats.Totals totals = stats.totals();
        long diskUsage = 0;
        long count = 0;
        for (List<CachedArtifact> artifacts : service.list().values()) {
            for (CachedArtifact artifact : artifacts) {
                diskUsage += Math.max(0, artifact.meta().size());
                count++;
            }
        }
        long requests = totals.hits() + totals.misses();
        double hitRate = requests == 0 ? 0 : (double) totals.hits() / requests;
        return new Stats(totals.hits(), totals.misses(), hitRate, totals.bytesServedFromCache(),
                totals.bytesDownloaded(), diskUsage, count, downloads.active().size(), cacheLocation.dir().toString(),
                cacheLocation.available(), cacheLocation.freeSpace());
    }

    @GetMapping("/api/downloads")
    public Downloads downloads() {
        return new Downloads(downloads.active(), downloads.recent());
    }

    @GetMapping("/api/events")
    public SseEmitter events() {
        return events.subscribe();
    }

    @ExceptionHandler(InvalidArtifactPathException.class)
    ResponseEntity<String> invalidPath(InvalidArtifactPathException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    private ArtifactView view(String repository, CachedArtifact artifact) {
        MavenCoordinates coordinates = MavenCoordinates.of(artifact.path());
        AccessStats.FileAccess access = stats.access(repository + "/" + artifact.path().value());
        return new ArtifactView(repository, artifact.path().value(), coordinates.group(), coordinates.artifact(),
                coordinates.version(), coordinates.file(), artifact.meta().size(), access.hits(), access.lastAccess(),
                artifact.meta().fetchedAt(), artifact.meta().upstreamUrl(), artifact.meta().checksumVerified());
    }

    private Repository repository(String name) {
        return service.repositories().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No repository " + name));
    }

    private static void requireAction(String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
    }
}
