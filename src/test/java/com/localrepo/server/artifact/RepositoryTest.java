package com.localrepo.server.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {

    @TempDir
    Path root;

    @Test
    void acceptsEverythingWithoutFilters() {
        Repository central = repository(List.of(), List.of());

        assertTrue(central.accepts(ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom")));
    }

    @Test
    void acceptsOnlyIncludedPaths() {
        Repository google = repository(List.of("androidx/**", "com/android/**"), List.of());

        assertTrue(google.accepts(ArtifactPath.of("androidx/core/core/1.13.1/core-1.13.1.pom")));
        assertTrue(google.accepts(ArtifactPath.of("com/android/tools/build/gradle/8.7.0/gradle-8.7.0.pom")));
        assertFalse(google.accepts(ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom")));
        assertFalse(google.accepts(ArtifactPath.of("com/androidx/fake/1/fake-1.pom")));
    }

    @Test
    void excludesWinOverIncludes() {
        Repository repository = repository(List.of("com/**"), List.of("com/secret/**"));

        assertTrue(repository.accepts(ArtifactPath.of("com/public/a/1/a-1.jar")));
        assertFalse(repository.accepts(ArtifactPath.of("com/secret/a/1/a-1.jar")));
    }

    @Test
    void buildsBasicAuthorization() {
        Repository repository = new Repository("private", "https://repo.example/m2", List.of(), List.of(),
                Repository.Credentials.basic("user", "secret"), store());

        // base64("user:secret")
        assertEquals("Basic dXNlcjpzZWNyZXQ=", repository.authorization().orElseThrow());
    }

    @Test
    void buildsBearerAuthorization() {
        Repository repository = new Repository("gh", "https://maven.pkg.github.com/me/repo", List.of(), List.of(),
                Repository.Credentials.bearer("t0ken"), store());

        assertEquals("Bearer t0ken", repository.authorization().orElseThrow());
    }

    @Test
    void sendsNoAuthorizationWithoutCredentials() {
        assertTrue(repository(List.of(), List.of()).authorization().isEmpty());
    }

    @Test
    void neverPrintsCredentials() {
        Repository repository = new Repository("private", "https://repo.example/m2", List.of(), List.of(),
                Repository.Credentials.basic("user", "secret"), store());

        assertFalse(repository.toString().contains("secret"));
    }

    @Test
    void rejectsNamesThatAreNotUrlSafe() {
        assertThrows(IllegalArgumentException.class, () -> new Repository("Not Safe/..", "https://x", List.of(),
                List.of(), Repository.Credentials.NONE, store()));
    }

    private Repository repository(List<String> includes, List<String> excludes) {
        return new Repository("test", "https://repo.example/maven2", includes, excludes, Repository.Credentials.NONE,
                store());
    }

    private ArtifactStore store() {
        return new ArtifactStore(root, Clock.systemUTC());
    }
}
