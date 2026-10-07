package com.localrepo.server.maintenance;

import com.localrepo.server.artifact.ArtifactPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrefetchListTest {

    @Test
    void expandsCoordinatesToPomModuleAndJar() {
        assertEquals(List.of(
                        "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.pom",
                        "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.module",
                        "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"),
                values(PrefetchList.parse("com.google.guava:guava:33.3.1-jre")));
    }

    @Test
    void readsClassifierAndExtension() {
        assertEquals(List.of(
                        "androidx/core/core/1.13.1/core-1.13.1.pom",
                        "androidx/core/core/1.13.1/core-1.13.1.module",
                        "androidx/core/core/1.13.1/core-1.13.1-sources.aar"),
                values(PrefetchList.parse("androidx.core:core:1.13.1:sources@aar")));
    }

    @Test
    void takesPathsAsTheyAreAndSkipsCommentsAndBlankLines() {
        assertEquals(List.of("junit/junit/4.13.2/junit-4.13.2.pom", "a/b/1/b-1.jar"),
                values(PrefetchList.parse("""
                        # what the build needs
                        junit/junit/4.13.2/junit-4.13.2.pom

                        a/b/1/b-1.jar
                        junit/junit/4.13.2/junit-4.13.2.pom
                        """)));
    }

    @Test
    void readsGradleVerificationMetadata() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
                   <components>
                      <component group="org.jetbrains.kotlin" name="kotlin-stdlib" version="2.1.0">
                         <artifact name="kotlin-stdlib-2.1.0.jar"><sha256 value="aa"/></artifact>
                         <artifact name="kotlin-stdlib-2.1.0.module"><sha256 value="bb"/></artifact>
                      </component>
                   </components>
                </verification-metadata>
                """;

        assertEquals(List.of("org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar",
                        "org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.module"),
                values(PrefetchList.parse(xml)));
    }

    @Test
    void rejectsSomethingThatIsNeitherACoordinateNorAPath() {
        assertThrows(IllegalArgumentException.class, () -> PrefetchList.parse("only:two"));
        assertThrows(IllegalArgumentException.class, () -> PrefetchList.parse("../etc/passwd"));
    }

    private static List<String> values(List<ArtifactPath> paths) {
        return paths.stream().map(ArtifactPath::value).toList();
    }
}
