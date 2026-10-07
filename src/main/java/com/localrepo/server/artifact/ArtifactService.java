package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serves artifacts from the cache, fetching misses from upstream repositories. A request either names one repository,
 * or goes to the group: every repository whose filters accept the path, in configured order.
 */
public class ArtifactService {

    public static final String GROUP = "group";

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    /** Replaced as a whole on every change, so requests always see one consistent list. */
    private volatile Map<String, Repository> repositories = Map.of();
    private final UpstreamClient upstreamClient;
    private final NegativeCache negativeCache;
    private final DownloadCoordinator downloads;
    private final FreshnessPolicy freshness;
    private final OfflineMode offline;

    public ArtifactService(List<Repository> repositories, UpstreamClient upstreamClient, NegativeCache negativeCache,
                           DownloadCoordinator downloads, FreshnessPolicy freshness, OfflineMode offline) {
        repositories.forEach(this::addRepository);
        this.upstreamClient = upstreamClient;
        this.negativeCache = negativeCache;
        this.downloads = downloads;
        this.freshness = freshness;
        this.offline = offline;
    }

    public sealed interface Resolution {
        /** {@code stale}: a changing file past its TTL, served without checking because the server is offline. */
        record Cached(CachedArtifact artifact, boolean stale) implements Resolution {
        }

        record Downloading(Download download) implements Resolution {
        }

        record Missing() implements Resolution {
        }
    }

    /** Adds an upstream, tried after the existing ones. */
    public synchronized void addRepository(Repository repository) {
        if (repository.name().equals(GROUP) || repositories.containsKey(repository.name())) {
            throw new IllegalArgumentException("Duplicate or reserved repository name: " + repository.name());
        }
        Map<String, Repository> updated = new LinkedHashMap<>(repositories);
        updated.put(repository.name(), repository);
        repositories = java.util.Collections.unmodifiableMap(updated);
    }

    /** Stops asking an upstream; what it served stays on disk. */
    public synchronized void removeRepository(String name) {
        if (!repositories.containsKey(name)) {
            throw new IllegalArgumentException("No repository " + name);
        }
        Map<String, Repository> updated = new LinkedHashMap<>(repositories);
        updated.remove(name);
        repositories = java.util.Collections.unmodifiableMap(updated);
    }

    public List<Repository> repositories() {
        return List.copyOf(repositories.values());
    }

    /** Resolves {@code path} in the group. Returns immediately: cached, a download that can be followed, or missing. */
    public Resolution resolve(ArtifactPath path) {
        List<Repository> candidates = repositories.values().stream().filter(r -> r.accepts(path)).toList();
        return resolve(GROUP, candidates, path);
    }

    /** Resolves {@code path} in one repository, ignoring its group filters. */
    public Resolution resolve(String repositoryName, ArtifactPath path) {
        Repository repository = repositories.get(repositoryName);
        if (repository == null) {
            return new Resolution.Missing();
        }
        return resolve(repositoryName, List.of(repository), path);
    }

    /** Resolves in the group and blocks until any download has finished. */
    public Optional<CachedArtifact> resolveAndWait(ArtifactPath path) throws IOException {
        return await(resolve(path));
    }

    /** Resolves in one repository and blocks until any download has finished. */
    public Optional<CachedArtifact> resolveAndWait(String repositoryName, ArtifactPath path) throws IOException {
        return await(resolve(repositoryName, path));
    }

    /** Every cached artifact, by repository. */
    public Map<Repository, List<CachedArtifact>> list() {
        Map<Repository, List<CachedArtifact>> all = new LinkedHashMap<>();
        repositories.values().forEach(r -> all.put(r, r.store().list()));
        return all;
    }

    private Resolution resolve(String scope, List<Repository> candidates, ArtifactPath path) {
        Optional<Hit> hit = findCached(candidates, path);
        if (hit.isPresent()) {
            CachedArtifact artifact = hit.get().artifact();
            if (!freshness.needsRevalidation(artifact)) {
                return new Resolution.Cached(artifact, false);
            }
            if (offline.isEnabled()) {
                return new Resolution.Cached(artifact, true);
            }
            return new Resolution.Downloading(downloads.join(scope + ":" + path.value(), path,
                    download -> revalidate(hit.get(), download)));
        }
        if (offline.isEnabled() || candidates.isEmpty() || negativeCache.isKnownMissing(scope, path)) {
            return new Resolution.Missing();
        }
        return new Resolution.Downloading(downloads.join(scope + ":" + path.value(), path,
                download -> fetch(scope, candidates, download)));
    }

    /** A cached artifact and the repository whose cache holds it. */
    private record Hit(Repository repository, CachedArtifact artifact) {
    }

    private static Optional<CachedArtifact> await(Resolution resolution) throws IOException {
        return switch (resolution) {
            case Resolution.Cached cached -> Optional.of(cached.artifact());
            case Resolution.Missing missing -> Optional.empty();
            case Resolution.Downloading downloading -> downloading.download().awaitResult();
        };
    }

    private static Optional<Hit> findCached(List<Repository> candidates, ArtifactPath path) {
        return candidates.stream()
                .flatMap(r -> r.store().find(path).map(a -> new Hit(r, a)).stream())
                .findFirst();
    }

    /** Asks the repository a changing file came from whether it changed; keeps the old copy if it cannot tell. */
    private void revalidate(Hit hit, Download download) {
        Repository repository = hit.repository();
        ArtifactPath path = download.path();
        try {
            try (UpstreamResponse response = upstreamClient.get(repository, path, hit.artifact().meta())) {
                if (response.isNotModified()) {
                    CachedArtifact refreshed = repository.store().refresh(path, response.origin());
                    download.complete(() -> refreshed);
                    log.debug("{} unchanged in {}", path.value(), repository.name());
                    return;
                }
                if (response.isOk()) {
                    transfer(repository, download, response);
                    return;
                }
                log.warn("{} answered {} revalidating {}; serving the cached copy", repository.name(),
                        response.status(), path.value());
            } catch (IOException e) {
                log.warn("Could not revalidate {} with {} ({}); serving the cached copy", path.value(),
                        repository.name(), e.toString());
            }
            download.completeStale(hit.artifact());
        } catch (IOException e) {
            download.failed(e);
        }
    }

    private void fetch(String scope, List<Repository> candidates, Download download) {
        ArtifactPath path = download.path();
        // Another request may have committed it between our cache check and starting this download.
        Optional<Hit> cached = findCached(candidates, path);
        if (cached.isPresent()) {
            try {
                download.complete(() -> cached.get().artifact());
            } catch (IOException e) {
                download.failed(e);
            }
            return;
        }

        boolean everyUpstreamSaidNotFound = true;
        for (Repository repository : candidates) {
            try (UpstreamResponse response = upstreamClient.get(repository, path)) {
                if (response.isOk()) {
                    transfer(repository, download, response);
                    return;
                }
                if (response.status() != 404 && response.status() != 410) {
                    everyUpstreamSaidNotFound = false;
                }
                log.debug("{} answered {} for {}", repository.name(), response.status(), path.value());
            } catch (IOException e) {
                everyUpstreamSaidNotFound = false;
                log.debug("Could not fetch {} from {}", path.value(), repository.name(), e);
            }
        }

        if (everyUpstreamSaidNotFound) {
            negativeCache.remember(scope, path);
        }
        log.info("{} not found in {}", path.value(), scope);
        download.notFound();
    }

    private static final List<String> CHECKSUM_SUFFIXES = List.of(".md5", ".sha1", ".sha256", ".sha512", ".asc");

    /**
     * {@code algorithm} is the checksum the bytes were compared with, null when the upstream publishes none;
     * {@code mismatch} means they differ.
     */
    private record Verification(String algorithm, boolean mismatch, String computed, String published) {
        static final Verification NONE = new Verification(null, false, null, null);
    }

    /**
     * Compares what was downloaded with the checksum the repository publishes for it, SHA-256 first, then SHA-1, and
     * caches the checksum file that matched.
     */
    private Verification verify(Repository repository, ArtifactPath path, ArtifactStore.Checksums checksums) {
        if (CHECKSUM_SUFFIXES.stream().anyMatch(path.fileName()::endsWith)) {
            return Verification.NONE;
        }
        for (String algorithm : List.of("sha256", "sha1")) {
            String computed = algorithm.equals("sha256") ? checksums.sha256() : checksums.sha1();
            ArtifactPath checksumPath = ArtifactPath.of(path.value() + "." + algorithm);
            try (UpstreamResponse response = upstreamClient.get(repository, checksumPath)) {
                if (!response.isOk()) {
                    continue;
                }
                byte[] body = response.body().readNBytes(1024);
                String published = parseChecksum(body, computed.length());
                if (published == null) {
                    log.debug("Ignoring unreadable {}", checksumPath.value());
                    continue;
                }
                if (!published.equals(computed)) {
                    return new Verification(algorithm, true, computed, published);
                }
                repository.store().save(checksumPath, new ByteArrayInputStream(body), response.origin());
                return new Verification(algorithm, false, computed, published);
            } catch (IOException e) {
                log.debug("Could not fetch {} from {}", checksumPath.value(), repository.name(), e);
            }
        }
        log.debug("{} publishes no checksum for {}", repository.name(), path.value());
        return Verification.NONE;
    }

    /** Checksum files hold the hex digest, sometimes followed by whitespace and the file name. */
    private static String parseChecksum(byte[] body, int expectedLength) {
        String[] tokens = new String(body, StandardCharsets.US_ASCII).trim().split("\\s+");
        String digest = tokens.length == 0 ? "" : tokens[0].toLowerCase(Locale.ROOT);
        return digest.length() == expectedLength && digest.chars().allMatch(c -> Character.digit(c, 16) >= 0)
                ? digest : null;
    }

    /**
     * Copies the body into the repository's store while readers follow it. Once bytes may have reached a client a
     * failure is final: falling back to another upstream could splice two different files together.
     */
    private void transfer(Repository repository, Download download, UpstreamResponse response) {
        ArtifactPath path = download.path();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (ArtifactStore.PendingWrite write = repository.store().begin(path)) {
            download.streaming(response.origin(), contentLength, write.partFile(), response.body());
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = response.body().read(buffer)) >= 0) {
                write.write(buffer, 0, read);
                download.progress(write.size());
            }
            if (contentLength >= 0 && write.size() != contentLength) {
                throw new IOException("Expected " + contentLength + " bytes but got " + write.size());
            }
            Verification verification = verify(repository, path, write.checksums());
            if (verification.mismatch()) {
                Path quarantined = write.quarantine();
                log.error("{} from {} does not match its published {} ({} != {}); quarantined at {}", path.value(),
                        repository.name(), verification.algorithm(), verification.computed(),
                        verification.published(), quarantined);
                throw new IOException("Checksum mismatch for " + path.value());
            }
            download.complete(() -> write.commit(response.origin(), verification.algorithm()));
            log.info("Cached {} from {} ({} bytes)", path.value(), repository.name(), write.size());
        } catch (IOException e) {
            log.warn("Download of {} from {} failed: {}", path.value(), response.url(), e.toString());
            download.failed(e);
        }
    }
}
