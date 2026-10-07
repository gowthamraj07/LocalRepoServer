package com.localrepo.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.localrepo.server.artifact.DownloadTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApiControllerTest {

    private static final String JAR = "/maven2/junit/junit/4.13.2/junit-4.13.2.jar";
    private static final String POM = "/maven2/junit/junit/4.13.2/junit-4.13.2.pom";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.NEVER))
            .build();

    @TempDir
    static Path cacheDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.upstreams[0].name", () -> "mock");
        registry.add("localrepo.upstreams[0].url", () -> upstream.baseUrl() + "/maven2");
        registry.add("localrepo.cache-dir", cacheDir::toString);
        registry.add("localrepo.negative-cache-ttl", () -> "0s");
    }

    @LocalServerPort
    int port;

    @Autowired
    DownloadTracker downloads;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        try (Stream<Path> files = Files.walk(cacheDir.resolve("mock")).sorted(java.util.Comparator.reverseOrder())) {
            files.forEach(p -> p.toFile().delete());
        } catch (java.nio.file.NoSuchFileException ignored) {
            // nothing cached yet
        }
        upstream.stubFor(any(anyUrl()).willReturn(notFound()));
        upstream.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        upstream.stubFor(get(POM).willReturn(ok("<project/>")));
    }

    @AfterEach
    void waitForDownloads() throws InterruptedException {
        while (!downloads.active().isEmpty()) {
            Thread.sleep(10);
        }
    }

    @Test
    void reportsWhereTheCacheIsAndHowMuchSpaceIsLeft() throws Exception {
        JsonNode stats = getJson("/api/stats");

        assertEquals(cacheDir.toAbsolutePath().normalize().toString(), stats.get("cacheDir").asText());
        assertTrue(stats.get("cacheAvailable").asBoolean());
        assertTrue(stats.get("freeSpace").asLong() > 0);
    }

    @Test
    void isDownWhileTheCacheDirectoryIsGoneSoGradleBuildsBypassIt() throws Exception {
        assertEquals(200, status("/actuator/health"));
        Path away = Files.move(cacheDir, cacheDir.resolveSibling(cacheDir.getFileName() + "-disconnected"));
        try {
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(uri("/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, health.statusCode());
            assertTrue(health.body().contains(cacheDir.getFileName().toString()), health.body());
            assertFalse(getJson("/api/stats").get("cacheAvailable").asBoolean());
        } finally {
            Files.move(away, cacheDir);
        }
        assertEquals(200, status("/actuator/health"));
    }

    @Test
    void listsCachedArtifactsWithCoordinatesAndHits() throws Exception {
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.pom");
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.pom");

        JsonNode page = getJson("/api/artifacts?q=4.13.2.POM");

        assertEquals(1, page.get("total").asInt());
        JsonNode item = page.get("items").get(0);
        assertEquals("mock", item.get("repository").asText());
        assertEquals("junit/junit/4.13.2/junit-4.13.2.pom", item.get("path").asText());
        assertEquals("junit", item.get("group").asText());
        assertEquals("4.13.2", item.get("version").asText());
        assertEquals(10, item.get("size").asLong());
        assertEquals(1, item.get("hits").asLong());
        assertFalse(item.get("lastAccess").isNull());
        assertEquals(0, getJson("/api/artifacts?q=nothing-like-this").get("total").asInt());
    }

    @Test
    void pagesThroughArtifacts() throws Exception {
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.pom");
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");

        JsonNode second = getJson("/api/artifacts?size=1&page=1");

        assertEquals(2, second.get("total").asInt());
        assertEquals(1, second.get("items").size());
        assertEquals("junit/junit/4.13.2/junit-4.13.2.pom", second.get("items").get(0).get("path").asText());
    }

    @Test
    void countsHitsMissesAndBytes() throws Exception {
        JsonNode before = getJson("/api/stats");

        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");

        JsonNode after = getJson("/api/stats");
        assertEquals(before.get("misses").asLong() + 1, after.get("misses").asLong());
        assertEquals(before.get("hits").asLong() + 1, after.get("hits").asLong());
        assertEquals(before.get("bytesServedFromCache").asLong() + 4, after.get("bytesServedFromCache").asLong());
        assertEquals(before.get("bytesDownloaded").asLong() + 4, after.get("bytesDownloaded").asLong());
        assertTrue(after.get("artifactCount").asLong() >= 1);
        assertTrue(after.get("diskUsage").asLong() >= 4);
        assertTrue(after.has("hitRate"));
    }

    @Test
    void deletesEverythingBelowAPath() throws Exception {
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.pom");
        URI uri = uri("/api/artifacts?repository=mock&path=junit/junit/4.13.2");

        assertEquals(403, http.send(HttpRequest.newBuilder(uri).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(uri).header("X-LocalRepo-Action", "true")
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(2, json.readTree(deleted.body()).get("deleted").asInt());
        assertEquals(0, getJson("/api/artifacts").get("total").asInt());
    }

    @Test
    void refetchesAFileFromItsRepository() throws Exception {
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");
        upstream.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{5, 6, 7, 8})));

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        uri("/api/artifacts/refetch?repository=mock&path=junit/junit/4.13.2/junit-4.13.2.jar"))
                .header("X-LocalRepo-Action", "true").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        waitForDownloads();

        assertEquals(202, response.statusCode());
        assertArrayEquals(new byte[]{5, 6, 7, 8},
                Files.readAllBytes(cacheDir.resolve("mock/junit/junit/4.13.2/junit-4.13.2.jar")));
    }

    @Test
    void reportsRecentDownloads() throws Exception {
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");

        JsonNode downloads = getJson("/api/downloads");

        assertTrue(downloads.has("active"));
        assertEquals("junit/junit/4.13.2/junit-4.13.2.jar", downloads.get("recent").get(0).get("path").asText());
        assertEquals("COMPLETED", downloads.get("recent").get(0).get("state").asText());
    }

    @Test
    void streamsDownloadAndCacheEvents() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        http.sendAsync(HttpRequest.newBuilder(uri("/api/events")).build(), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(lines::add));
        while (lines.stream().noneMatch(l -> l.startsWith("event:connected"))) {
            Thread.sleep(10);
        }

        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");
        fetchAndSettle("junit/junit/4.13.2/junit-4.13.2.jar");

        while (lines.stream().noneMatch(l -> l.startsWith("event:cache-hit"))) {
            Thread.sleep(10);
        }
        assertTrue(lines.contains("event:download-started"), lines.toString());
        assertTrue(lines.contains("event:download-completed"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("data:") && l.contains("junit-4.13.2.jar")));
    }

    @Test
    void theOldListEndpointIsGone() throws Exception {
        assertEquals(404, http.send(HttpRequest.newBuilder(uri("/list")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private void fetchAndSettle(String path) throws Exception {
        assertEquals(200, http.send(HttpRequest.newBuilder(uri("/cache/" + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        waitForDownloads();
    }

    private int status(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
