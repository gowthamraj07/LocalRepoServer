package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Serves artifacts from the store, fetching misses from the configured upstreams in order. */
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactStore store;
    private final UpstreamClient upstreamClient;
    private final List<String> upstreams;
    private final NegativeCache negativeCache;

    public ArtifactService(ArtifactStore store, UpstreamClient upstreamClient, List<String> upstreams,
                           NegativeCache negativeCache) {
        this.store = store;
        this.upstreamClient = upstreamClient;
        this.upstreams = List.copyOf(upstreams);
        this.negativeCache = negativeCache;
    }

    public Optional<CachedArtifact> resolve(ArtifactPath path) {
        Optional<CachedArtifact> cached = store.find(path);
        if (cached.isPresent()) {
            return cached;
        }
        if (negativeCache.isKnownMissing(path)) {
            return Optional.empty();
        }

        boolean everyUpstreamSaidNotFound = true;
        for (String upstream : upstreams) {
            try (UpstreamResponse response = upstreamClient.get(upstream, path)) {
                if (response.isOk()) {
                    CachedArtifact artifact = store.save(path, response.body(), response.origin());
                    log.info("Cached {} from {}", path.value(), upstream);
                    return Optional.of(artifact);
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
        return Optional.empty();
    }

    public List<CachedArtifact> list() {
        return store.list();
    }
}
