package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.Repository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalRepoPropertiesTest {

    @Nested
    @SpringBootTest(args = "--repos=https://a.example/maven2,https://b.example/m2,https://a.example/other")
    class LegacyReposArgument {
        @Autowired
        ArtifactService service;

        @Test
        void replacesTheUpstreamsWithUnfilteredOnesNamedAfterTheirHosts() {
            List<Repository> repositories = service.repositories();

            assertEquals(List.of("a-example", "b-example", "a-example-2"), repositories.stream().map(Repository::name).toList());
            assertEquals(List.of("https://a.example/maven2", "https://b.example/m2", "https://a.example/other"),
                    repositories.stream().map(Repository::url).toList());
            assertTrue(repositories.stream().allMatch(r -> r.accepts(ArtifactPath.of("androidx/a/1/a-1.pom"))));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "localrepo.upstreams[0].name=private",
            "localrepo.upstreams[0].url=https://repo.example/m2",
            "localrepo.upstreams[0].includes[0]=com/example/**",
            "localrepo.upstreams[0].credentials.token-env=TEST_REPO_TOKEN",
            "TEST_REPO_TOKEN=s3cret"})
    class ConfiguredUpstreams {
        @Autowired
        ArtifactService service;

        @Test
        void bindsNameUrlFiltersAndCredentials() {
            Repository repository = service.repositories().getFirst();

            assertEquals("private", repository.name());
            assertEquals("https://repo.example/m2", repository.url());
            assertTrue(repository.accepts(ArtifactPath.of("com/example/a/1/a-1.jar")));
            assertFalse(repository.accepts(ArtifactPath.of("org/other/a/1/a-1.jar")));
            assertEquals("Bearer s3cret", repository.authorization().orElseThrow());
        }
    }

    @Nested
    @SpringBootTest
    class Defaults {
        @Autowired
        ArtifactService service;

        @Test
        void shipsTheRepositoriesAndroidAndMultiplatformBuildsNeed() {
            assertEquals(List.of("google", "central", "gradle-plugins", "jetbrains-compose", "jitpack"),
                    service.repositories().stream().map(Repository::name).toList());
        }

        @Test
        void routesAndroidxToGoogleButNotToJitpack() {
            ArtifactPath androidx = ArtifactPath.of("androidx/core/core/1.13.1/core-1.13.1.pom");
            List<String> askedFor = service.repositories().stream().filter(r -> r.accepts(androidx))
                    .map(Repository::name).toList();

            assertEquals(List.of("google", "central", "gradle-plugins"), askedFor);
        }
    }
}
