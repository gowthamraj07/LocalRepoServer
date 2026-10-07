package com.localrepo.server.artifact;

import com.localrepo.server.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshnessPolicyTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final FreshnessPolicy policy = new FreshnessPolicy(Duration.ofHours(24), clock);

    @ParameterizedTest
    @ValueSource(strings = {
            "junit/junit/maven-metadata.xml",
            "junit/junit/maven-metadata.xml.sha1",
            "com/example/lib/1.0-SNAPSHOT/lib-1.0-20261007.101010-3.jar",
            "com/example/lib/1.0-SNAPSHOT/maven-metadata.xml"})
    void treatsMetadataAndSnapshotsAsChanging(String path) {
        assertTrue(policy.isMutable(ArtifactPath.of(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"junit/junit/4.13.2/junit-4.13.2.jar", "junit/junit/4.13.2/junit-4.13.2.pom.sha1",
            "com/example/snapshot-tools/1.0/snapshot-tools-1.0.jar"})
    void treatsReleasesAsImmutable(String path) {
        assertFalse(policy.isMutable(ArtifactPath.of(path)));
    }

    @Test
    void aReleaseIsNeverStale() {
        CachedArtifact release = cached("junit/junit/4.13.2/junit-4.13.2.jar", clock.instant());
        clock.advance(Duration.ofDays(3650));

        assertFalse(policy.needsRevalidation(release));
    }

    @Test
    void metadataGoesStaleAfterTheTtl() {
        CachedArtifact metadata = cached("junit/junit/maven-metadata.xml", clock.instant());

        clock.advance(Duration.ofHours(23));
        assertFalse(policy.needsRevalidation(metadata));

        clock.advance(Duration.ofHours(2));
        assertTrue(policy.needsRevalidation(metadata));
    }

    private static CachedArtifact cached(String path, Instant fetchedAt) {
        return new CachedArtifact(ArtifactPath.of(path), null, new ArtifactMeta(null, null, null, fetchedAt, 1, null));
    }
}
