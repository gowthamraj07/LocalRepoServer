package com.localrepo.server.setup;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import com.localrepo.server.artifact.OfflineMode;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Runs real Gradle builds with the served init script against a fake upstream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradleInitScriptTest {

    /** Refuses connections: a repository that can only work if the proxy is in front of it. */
    private static final String DEAD_REPO = "http://127.0.0.1:1/m2";
    private static final String LIB = "com/example/lib/1.0/lib-1.0";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    static Path cacheDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.upstreams[0].name", () -> "mock");
        registry.add("localrepo.upstreams[0].url", () -> upstream.baseUrl() + "/proxied");
        registry.add("localrepo.cache-dir", cacheDir::toString);
        registry.add("localrepo.negative-cache-ttl", () -> "0s");
    }

    @LocalServerPort
    int port;

    @Autowired
    OfflineMode offline;

    @TempDir
    Path project;
    private Path initScript;

    @BeforeEach
    void setUp() throws Exception {
        try (Stream<Path> files = Files.walk(cacheDir)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(cacheDir)).forEach(p -> p.toFile().delete());
        }
        for (String prefix : List.of("/proxied/", "/direct/")) {
            upstream.stubFor(any(urlEqualTo(prefix + LIB + ".pom")).willReturn(ok("""
                    <project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>
                    <artifactId>lib</artifactId><version>1.0</version></project>""")));
            upstream.stubFor(any(urlEqualTo(prefix + LIB + ".jar")).willReturn(ok().withBody(new byte[]{'P', 'K'})));
        }
        String script = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/setup/gradle/localrepo.init.gradle")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        initScript = Files.writeString(project.resolve("localrepo.init.gradle"), script);
    }

    @AfterEach
    void backOnline() {
        offline.set(false);
    }

    @Test
    void resolvesAVersionRangeOfflineThoughTheBuildDeclaresAnUnreachableRepository() throws Exception {
        // Gradle lists the versions of a range in every repository and fails if one is unreachable, so while the
        // server is offline it has to be the only repository the build asks.
        stubMetadata();
        for (String file : List.of("com/example/lib/maven-metadata.xml", LIB + ".pom", LIB + ".jar")) {
            assertEquals(200, HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/cache/" + file)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode(), file);
        }
        offline.set(true);
        writeSettings("");
        writeBuild("repositories { maven { url = '%s'; allowInsecureProtocol = true } }".formatted(DEAD_REPO),
                "com.example:lib:[1.0,2.0)");

        GradleRunner.Result result = gradle("resolveLib");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("RESOLVED lib-1.0.jar"), result.output());
        assertTrue(result.output().contains("LocalRepoServer is offline"), result.output());
    }

    @Test
    void canBeTheOnlyRepositoryOnRequest() throws Exception {
        stubMetadata();
        writeSettings("");
        writeBuild("repositories { maven { url = '%s/direct'; allowInsecureProtocol = true } }".formatted(upstream.baseUrl()),
                "com.example:lib:[1.0,2.0)");

        GradleRunner.Result result = gradle("resolveLib", "-Plocalrepo.exclusive=true");

        assertEquals(0, result.exitCode(), result.output());
        upstream.verify(0, anyRequestedFor(urlMatching("/direct/.*")));
    }

    @Test
    void servesTheScriptPointingAtThisServer() throws Exception {
        assertTrue(Files.readString(initScript).contains("'http://127.0.0.1:" + port + "'"));
    }

    @Test
    void proxiesRepositoriesDeclaredInSettings() throws Exception {
        writeSettings("""
                dependencyResolutionManagement {
                    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
                    repositories { maven { url = '%s'; allowInsecureProtocol = true } }
                }
                """.formatted(DEAD_REPO));
        writeBuild("");

        GradleRunner.Result result = gradle("resolveLib");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("RESOLVED lib-1.0.jar"), result.output());
        upstream.verify(getRequestedFor(urlEqualTo("/proxied/" + LIB + ".jar")));
    }

    @Test
    void proxiesRepositoriesDeclaredInTheProject() throws Exception {
        writeSettings("");
        writeBuild("repositories { maven { url = '%s'; allowInsecureProtocol = true } }".formatted(DEAD_REPO));

        GradleRunner.Result result = gradle("resolveLib");

        assertEquals(0, result.exitCode(), result.output());
        upstream.verify(getRequestedFor(urlEqualTo("/proxied/" + LIB + ".jar")));
    }

    @Test
    void keepsTheBuildsOwnRepositoriesBehindTheProxy() throws Exception {
        upstream.stubFor(any(urlMatching("/proxied/.*")).willReturn(notFound()));
        writeSettings("");
        writeBuild("repositories { maven { url = '%s/direct'; allowInsecureProtocol = true } }".formatted(upstream.baseUrl()));

        GradleRunner.Result result = gradle("resolveLib");

        assertEquals(0, result.exitCode(), result.output());
        upstream.verify(getRequestedFor(urlEqualTo("/proxied/" + LIB + ".pom")));
        upstream.verify(getRequestedFor(urlEqualTo("/direct/" + LIB + ".jar")));
    }

    @Test
    void resolvesPluginsThroughTheProxy() throws Exception {
        writeSettings("");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'com.example.hello' version '1.0' }\n");

        gradle("help");

        upstream.verify(getRequestedFor(urlPathMatching(
                "/proxied/com/example/hello/com.example.hello.gradle.plugin/1.0/.*")));
    }

    @Test
    void leavesTheBuildAloneWhenTheServerIsDown() throws Exception {
        Files.writeString(initScript, Files.readString(initScript).replace("'http://127.0.0.1:" + port + "'", "'" + DEAD_REPO + "'"));
        writeSettings("");
        writeBuild("repositories { maven { url = '%s/direct'; allowInsecureProtocol = true } }".formatted(upstream.baseUrl()));

        GradleRunner.Result result = gradle("resolveLib");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("LocalRepoServer is not running"), result.output());
        upstream.verify(0, anyRequestedFor(urlMatching("/proxied/.*")));
    }

    @Test
    void canBeDisabledForOneBuild() throws Exception {
        writeSettings("");
        writeBuild("repositories { maven { url = '%s/direct'; allowInsecureProtocol = true } }".formatted(upstream.baseUrl()));

        GradleRunner.Result result = gradle("resolveLib", "-Plocalrepo.disabled=true");

        assertEquals(0, result.exitCode(), result.output());
        upstream.verify(0, anyRequestedFor(urlMatching("/proxied/.*")));
    }

    private void stubMetadata() {
        for (String prefix : List.of("/proxied/", "/direct/")) {
            upstream.stubFor(any(urlEqualTo(prefix + "com/example/lib/maven-metadata.xml")).willReturn(ok("""
                    <metadata><groupId>com.example</groupId><artifactId>lib</artifactId><versioning><latest>1.0</latest>
                    <release>1.0</release><versions><version>1.0</version></versions></versioning></metadata>""")));
        }
    }

    private GradleRunner.Result gradle(String... arguments) throws Exception {
        List<String> all = new ArrayList<>(List.of("--init-script", initScript.toString(), "--refresh-dependencies"));
        all.addAll(List.of(arguments));
        return GradleRunner.run(project, all);
    }

    private void writeSettings(String content) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), content + "\nrootProject.name = 'fixture'\n");
    }

    private void writeBuild(String repositories) throws Exception {
        writeBuild(repositories, "com.example:lib:1.0");
    }

    private void writeBuild(String repositories, String dependency) throws Exception {
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'java' }
                %s
                dependencies { implementation '%s' }
                tasks.register('resolveLib') {
                    def classpath = configurations.runtimeClasspath
                    doLast { classpath.files.each { println "RESOLVED ${it.name}" } }
                }
                """.formatted(repositories, dependency));
    }
}
