package com.localrepo.server.artifact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers paths that every upstream reported as missing, so repeated lookups don't hit the network. */
public class NegativeCache {

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();

    public NegativeCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public void remember(ArtifactPath path) {
        if (!ttl.isZero() && !ttl.isNegative()) {
            expiries.put(path.value(), clock.instant().plus(ttl));
        }
    }

    public boolean isKnownMissing(ArtifactPath path) {
        Instant expiry = expiries.get(path.value());
        if (expiry == null) {
            return false;
        }
        if (clock.instant().isBefore(expiry)) {
            return true;
        }
        expiries.remove(path.value(), expiry);
        return false;
    }

    public void forget(ArtifactPath path) {
        expiries.remove(path.value());
    }
}
