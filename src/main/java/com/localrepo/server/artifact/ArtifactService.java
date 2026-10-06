package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final Map<String, Repository> repositories = new LinkedHashMap<>();
    private final UpstreamClient upstreamClient;
    private final NegativeCache negativeCache;
    private final DownloadCoordinator downloads;

    public ArtifactService(List<Repository> repositories, UpstreamClient upstreamClient, NegativeCache negativeCache,
                           DownloadCoordinator downloads) {
        for (Repository repository : repositories) {
            if (repository.name().equals(GROUP) || this.repositories.putIfAbsent(repository.name(), repository) != null) {
                throw new IllegalArgumentException("Duplicate or reserved repository name: " + repository.name());
            }
        }
        this.upstreamClient = upstreamClient;
        this.negativeCache = negativeCache;
        this.downloads = downloads;
    }

    public sealed interface Resolution {
        record Cached(CachedArtifact artifact) implements Resolution {
        }

        record Downloading(Download download) implements Resolution {
        }

        record Missing() implements Resolution {
        }
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
        Optional<CachedArtifact> cached = findCached(candidates, path);
        if (cached.isPresent()) {
            return new Resolution.Cached(cached.get());
        }
        if (candidates.isEmpty() || negativeCache.isKnownMissing(scope, path)) {
            return new Resolution.Missing();
        }
        return new Resolution.Downloading(downloads.join(scope + ":" + path.value(), path,
                download -> fetch(scope, candidates, download)));
    }

    private static Optional<CachedArtifact> await(Resolution resolution) throws IOException {
        return switch (resolution) {
            case Resolution.Cached cached -> Optional.of(cached.artifact());
            case Resolution.Missing missing -> Optional.empty();
            case Resolution.Downloading downloading -> downloading.download().awaitResult();
        };
    }

    private static Optional<CachedArtifact> findCached(List<Repository> candidates, ArtifactPath path) {
        return candidates.stream().flatMap(r -> r.store().find(path).stream()).findFirst();
    }

    private void fetch(String scope, List<Repository> candidates, Download download) {
        ArtifactPath path = download.path();
        // Another request may have committed it between our cache check and starting this download.
        Optional<CachedArtifact> cached = findCached(candidates, path);
        if (cached.isPresent()) {
            try {
                download.complete(cached::get);
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
            download.complete(() -> write.commit(response.origin()));
            log.info("Cached {} from {} ({} bytes)", path.value(), repository.name(), write.size());
        } catch (IOException e) {
            log.warn("Download of {} from {} failed: {}", path.value(), response.url(), e.toString());
            download.failed(e);
        }
    }
}
