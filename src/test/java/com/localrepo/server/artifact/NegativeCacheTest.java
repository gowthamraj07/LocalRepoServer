package com.localrepo.server.artifact;

import com.localrepo.server.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NegativeCacheTest {

    private static final ArtifactPath PATH = ArtifactPath.of("no/such/1/x.pom");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));

    @Test
    void remembersAMissUntilTheTtlExpires() {
        NegativeCache cache = new NegativeCache(Duration.ofMinutes(5), clock);

        cache.remember("group", PATH);
        clock.advance(Duration.ofMinutes(4));
        assertTrue(cache.isKnownMissing("group", PATH));

        clock.advance(Duration.ofMinutes(2));
        assertFalse(cache.isKnownMissing("group", PATH));
    }

    @Test
    void zeroTtlDisablesTheCache() {
        NegativeCache cache = new NegativeCache(Duration.ZERO, clock);

        cache.remember("group", PATH);

        assertFalse(cache.isKnownMissing("group", PATH));
    }

    @Test
    void forgetsAPathOnRequest() {
        NegativeCache cache = new NegativeCache(Duration.ofMinutes(5), clock);
        cache.remember("group", PATH);

        cache.forget("group", PATH);

        assertFalse(cache.isKnownMissing("group", PATH));
    }

    @Test
    void keepsScopesApart() {
        NegativeCache cache = new NegativeCache(Duration.ofMinutes(5), clock);

        cache.remember("group", PATH);

        assertFalse(cache.isKnownMissing("google", PATH));
    }
}
