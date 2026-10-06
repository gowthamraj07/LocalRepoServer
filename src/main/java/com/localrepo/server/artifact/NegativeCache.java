package com.localrepo.server.artifact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers paths that every upstream in a scope (the group, or one named repository) reported as missing, so repeated
 * lookups don't hit the network.
 */
public class NegativeCache {

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();

    public NegativeCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public void remember(String scope, ArtifactPath path) {
        if (!ttl.isZero() && !ttl.isNegative()) {
            expiries.put(key(scope, path), clock.instant().plus(ttl));
        }
    }

    public boolean isKnownMissing(String scope, ArtifactPath path) {
        String key = key(scope, path);
        Instant expiry = expiries.get(key);
        if (expiry == null) {
            return false;
        }
        if (clock.instant().isBefore(expiry)) {
            return true;
        }
        expiries.remove(key, expiry);
        return false;
    }

    public void forget(String scope, ArtifactPath path) {
        expiries.remove(key(scope, path));
    }

    private static String key(String scope, ArtifactPath path) {
        return scope + ":" + path.value();
    }
}
