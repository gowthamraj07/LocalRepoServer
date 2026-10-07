package com.localrepo.server.api;

import com.localrepo.server.artifact.ArtifactPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MavenCoordinatesTest {

    @Test
    void readsGroupArtifactVersionAndFile() {
        MavenCoordinates c = MavenCoordinates.of(ArtifactPath.of("org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar"));

        assertEquals("org.jetbrains.kotlin", c.group());
        assertEquals("kotlin-stdlib", c.artifact());
        assertEquals("2.1.0", c.version());
        assertEquals("kotlin-stdlib-2.1.0.jar", c.file());
    }

    @Test
    void readsArtifactLevelMetadataWithoutAVersion() {
        MavenCoordinates c = MavenCoordinates.of(ArtifactPath.of("junit/junit/maven-metadata.xml"));

        assertEquals("junit", c.group());
        assertEquals("junit", c.artifact());
        assertNull(c.version());
    }

    @Test
    void keepsShortPathsAsFilesOnly() {
        MavenCoordinates c = MavenCoordinates.of(ArtifactPath.of("archetype-catalog.xml"));

        assertNull(c.group());
        assertEquals("archetype-catalog.xml", c.file());
    }
}
