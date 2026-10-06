package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Serves artifacts from the store, fetching misses from the configured upstreams in order. */
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final ArtifactStore store;
    private final UpstreamClient upstreamClient;
    private final List<String> upstreams;
    private final NegativeCache negativeCache;
    private final DownloadCoordinator downloads;

    public ArtifactService(ArtifactStore store, UpstreamClient upstreamClient, List<String> upstreams,
                           NegativeCache negativeCache, DownloadCoordinator downloads) {
        this.store = store;
        this.upstreamClient = upstreamClient;
        this.upstreams = List.copyOf(upstreams);
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

    /** Returns immediately: either the cached artifact, a download that can be followed, or a known miss. */
    public Resolution resolve(ArtifactPath path) {
        Optional<CachedArtifact> cached = store.find(path);
        if (cached.isPresent()) {
            return new Resolution.Cached(cached.get());
        }
        if (negativeCache.isKnownMissing(path)) {
            return new Resolution.Missing();
        }
        return new Resolution.Downloading(downloads.join(path, this::fetch));
    }

    /** Resolves and blocks until any download has finished. */
    public Optional<CachedArtifact> resolveAndWait(ArtifactPath path) throws IOException {
        return switch (resolve(path)) {
            case Resolution.Cached cached -> Optional.of(cached.artifact());
            case Resolution.Missing missing -> Optional.empty();
            case Resolution.Downloading downloading -> downloading.download().awaitResult();
        };
    }

    public List<CachedArtifact> list() {
        return store.list();
    }

    private void fetch(Download download) {
        ArtifactPath path = download.path();
        // Another request may have committed it between our cache check and starting this download.
        Optional<CachedArtifact> cached = store.find(path);
        if (cached.isPresent()) {
            try {
                download.complete(cached::get);
            } catch (IOException e) {
                download.failed(e);
            }
            return;
        }

        boolean everyUpstreamSaidNotFound = true;
        for (String upstream : upstreams) {
            try (UpstreamResponse response = upstreamClient.get(upstream, path)) {
                if (response.isOk()) {
                    transfer(download, response);
                    return;
                }
                if (response.status() != 404 && response.status() != 410) {
                    everyUpstreamSaidNotFound = false;
                }
                log.debug("{} answered {} for {}", upstream, response.status(), path.value());
            } catch (IOException e) {
                everyUpstreamSaidNotFound = false;
                log.debug("Could not fetch {} from {}", path.value(), upstream, e);
            }
        }

        if (everyUpstreamSaidNotFound) {
            negativeCache.remember(path);
        }
        log.info("{} not found on any upstream", path.value());
        download.notFound();
    }

    /**
     * Copies the body into the store while readers follow it. Once bytes may have reached a client a failure is final:
     * falling back to another upstream could splice two different files together.
     */
    private void transfer(Download download, UpstreamResponse response) {
        ArtifactPath path = download.path();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (ArtifactStore.PendingWrite write = store.begin(path)) {
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
            log.info("Cached {} from {} ({} bytes)", path.value(), response.url(), write.size());
        } catch (IOException e) {
            log.warn("Download of {} from {} failed: {}", path.value(), response.url(), e.toString());
            download.failed(e);
        }
    }
}
