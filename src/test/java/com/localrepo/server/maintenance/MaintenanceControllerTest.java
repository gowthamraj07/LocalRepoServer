package com.localrepo.server.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.OfflineMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaintenanceControllerTest {

    private static final String JAR = "junit/junit/4.13.2/junit-4.13.2.jar";
    private static final String POM = "junit/junit/4.13.2/junit-4.13.2.pom";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

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

    @Autowired
    OfflineMode offline;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        try (Stream<Path> files = Files.walk(cacheDir)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(cacheDir)).forEach(p -> p.toFile().delete());
        }
        upstream.stubFor(any(anyUrl()).willReturn(notFound()));
        upstream.stubFor(get("/maven2/" + JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        upstream.stubFor(get("/maven2/" + POM).willReturn(ok("<project/>")));
    }

    @AfterEach
    void online() {
        offline.set(false);
    }

    @Test
    void exportsABundleThatAnotherCacheCanImportAndServeOffline() throws Exception {
        fetch(JAR);
        fetch(POM);

        byte[] bundle = export("");
        Map<String, byte[]> entries = unzip(bundle);
        assertTrue(entries.containsKey("localrepo-bundle.json"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, entries.get("mock/" + JAR));
        assertTrue(entries.containsKey("mock/" + JAR + ".meta.json"));

        delete("mock", "junit");
        assertEquals(404, statusOfOffline(JAR));

        JsonNode result = importBundle(bundle);
        assertEquals(2, result.get("imported").asInt(), result.toString());
        assertEquals(200, statusOfOffline(JAR));
        assertEquals(0, importBundle(bundle).get("imported").asInt(), "existing files are kept");
    }

    @Test
    void exportsOnlyWhatWasUsedRecently() throws Exception {
        fetch(JAR);
        String since = java.time.Instant.now().plusSeconds(1).toString();
        Thread.sleep(1100);
        fetch(POM);
        fetch(POM);

        Map<String, byte[]> entries = unzip(export("?usedSince=" + since));

        assertTrue(entries.containsKey("mock/" + POM));
        assertFalse(entries.containsKey("mock/" + JAR));
    }

    @Test
    void rejectsTamperedTraversingAndForeignEntries() throws Exception {
        fetch(JAR);
        Map<String, byte[]> entries = unzip(export(""));
        entries.put("mock/" + JAR, new byte[]{9, 9, 9, 9});
        entries.put("mock/../../escaped.jar", new byte[]{1});
        entries.put("elsewhere/a/a/1/a-1.jar", new byte[]{1});
        delete("mock", "junit");

        JsonNode result = importBundle(zip(entries));

        assertEquals(0, result.get("imported").asInt());
        assertEquals(1, result.get("unknownRepository").asInt());
        assertEquals(2, result.get("rejected").size(), result.toString());
        assertFalse(Files.exists(cacheDir.resolve("mock").resolve(JAR)));
        assertFalse(Files.exists(cacheDir.getParent().resolve("escaped.jar")));
    }

    @Test
    void purgesAndEvictsOnRequest() throws Exception {
        fetch(JAR);
        fetch(POM);

        HttpResponse<String> purged = post("/api/purge?path=junit/junit/4.13.2/junit-4.13.2.jar", null);
        assertEquals(List.of("mock/" + JAR), json.convertValue(json.readTree(purged.body()).get("purged"), List.class));
        assertEquals(400, post("/api/purge", null).statusCode());

        JsonNode evicted = json.readTree(post("/api/evict", null).body());
        assertEquals(0, evicted.get("deleted").size(), "no limit configured");
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/api/evict")).POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private void fetch(String path) throws Exception {
        assertEquals(200, http.send(HttpRequest.newBuilder(uri("/cache/" + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        while (!downloads.active().isEmpty()) {
            Thread.sleep(10);
        }
    }

    private int statusOfOffline(String path) throws Exception {
        offline.set(true);
        try {
            return http.send(HttpRequest.newBuilder(uri("/cache/" + path)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } finally {
            offline.set(false);
        }
    }

    private byte[] export(String query) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri("/api/export" + query)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains(".zip"));
        return response.body();
    }

    private JsonNode importBundle(byte[] bundle) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/import"))
                .header("X-LocalRepo-Action", "true").header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bundle)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void delete(String repository, String path) throws Exception {
        http.send(HttpRequest.newBuilder(uri("/api/artifacts?repository=" + repository + "&path=" + path))
                .header("X-LocalRepo-Action", "true").DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("X-LocalRepo-Action", "true")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
