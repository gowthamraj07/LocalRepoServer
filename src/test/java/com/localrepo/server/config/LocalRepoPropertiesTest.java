package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.Repository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    @Test
    void addsExtraUpstreamsFromTheUserFilesAfterTheDefaultsWithCredentialsFromGradleProperties(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        java.nio.file.Files.writeString(home.resolve("upstreams.yml"), """
                localrepo:
                  extra-upstreams:
                    - name: github-mobile-deps
                      url: https://maven.pkg.github.com/me/mobile-deps
                      includes: [ "io/github/me/**" ]
                      credentials: { gradle-property-username: gpr.user, gradle-property-password: gpr.key }
                """);
        java.nio.file.Files.writeString(home.resolve("config.yml"), "localrepo:\n  offline: true\n");
        java.nio.file.Files.createDirectories(home.resolve("gradle"));
        java.nio.file.Files.writeString(home.resolve("gradle/gradle.properties"), "gpr.user=me\ngpr.key=s3cret\n");

        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(
                com.localrepo.server.ServerApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--localrepo.home=" + home, "--localrepo.gradle-user-home=" + home.resolve("gradle"),
                        "--localrepo.cache-dir=" + home.resolve("cache"))) {
            List<Repository> repositories = context.getBean(ArtifactService.class).repositories();

            assertEquals(List.of("google", "central", "gradle-plugins", "jetbrains-compose", "jitpack", "github-mobile-deps"),
                    repositories.stream().map(Repository::name).toList());
            Repository github = repositories.getLast();
            assertTrue(github.accepts(ArtifactPath.of("io/github/me/catalog/1/catalog-1.pom")));
            // base64("me:s3cret")
            assertEquals("Basic bWU6czNjcmV0", github.authorization().orElseThrow());
            assertTrue(context.getBean(LocalRepoProperties.class).offline(), "config.yml is applied");
        }
    }

    @Test
    void explainsAnUpstreamWithoutAName() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocalRepoConfiguration.class)
                .withPropertyValues("localrepo.upstreams[0].url=https://repo.example/m2")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
                        cause = cause.getCause();
                    }
                    assertTrue(cause.getMessage().contains("localrepo.upstreams[0] needs a name"), cause.getMessage());
                });
    }

    @Nested
    // An empty home, so the config.yml and upstreams.yml of whoever runs the tests are not applied.
    @SpringBootTest(properties = "localrepo.home=${java.io.tmpdir}/localrepo-test-empty-home")
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
