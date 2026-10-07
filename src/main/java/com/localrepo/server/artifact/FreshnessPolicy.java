package com.localrepo.server.artifact;

import java.time.Clock;
import java.time.Duration;

/**
 * Released artifacts never change, so once cached they are served forever. Version listings
 * ({@code maven-metadata.xml}) and anything below a {@code -SNAPSHOT} version do change, and are checked with the
 * upstream again once older than the TTL.
 */
public class FreshnessPolicy {

    private final Duration mutableTtl;
    private final Clock clock;

    public FreshnessPolicy(Duration mutableTtl, Clock clock) {
        this.mutableTtl = mutableTtl;
        this.clock = clock;
    }

    public boolean isMutable(ArtifactPath path) {
        if (path.fileName().startsWith("maven-metadata.xml")) {
            return true;
        }
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        for (String segment : value.substring(0, Math.max(0, lastSlash)).split("/")) {
            if (segment.endsWith("-SNAPSHOT")) {
                return true;
            }
        }
        return false;
    }

    public boolean needsRevalidation(CachedArtifact artifact) {
        return isMutable(artifact.path())
                && !artifact.meta().fetchedAt().plus(mutableTtl).isAfter(clock.instant());
    }
}
