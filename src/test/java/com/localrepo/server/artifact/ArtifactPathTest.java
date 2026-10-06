package com.localrepo.server.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactPathTest {

    @Test
    void keepsAValidMavenPath() {
        assertEquals("junit/junit/4.13.2/junit-4.13.2.pom",
                ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom").value());
    }

    @Test
    void decodesPercentEncoding() {
        assertEquals("a/b c/1/x.pom", ArtifactPath.of("a/b%20c/1/x.pom").value());
    }

    @Test
    void collapsesDuplicateSlashes() {
        assertEquals("a/b/x.pom", ArtifactPath.of("a//b/x.pom").value());
    }

    @Test
    void exposesFileName() {
        assertEquals("x.pom", ArtifactPath.of("a/b/x.pom").fileName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "../etc/passwd", "a/../../etc", "a/%2e%2e/b", "/abs/path", "a\\b", "a/./b",
            "a/b/x.pom.meta.json", "a/b/x.pom.part", "a/b/\u0000"})
    void rejectsUnsafeOrInternalPaths(String raw) {
        assertThrows(InvalidArtifactPathException.class, () -> ArtifactPath.of(raw));
    }
}
