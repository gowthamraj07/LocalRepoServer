package com.localrepo.server.artifact;

import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ArtifactControllerTest {

    private static final String POM = "/maven2/junit/junit/4.13.2/junit-4.13.2.pom";
    private static final String JAR = "/maven2/junit/junit/4.13.2/junit-4.13.2.jar";
    private static final String BIG = "/maven2/big/big/1/big-1.aar";
    private static final byte[] BIG_BODY = new byte[256 * 1024];

    static {
        new Random(42).nextBytes(BIG_BODY);
    }

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.NEVER))
            .build();

    @TempDir
    static Path cacheDir;

    /** An upstream that promises 100 KB, sends 10 KB, then drops the connection. */
    static final ServerSocket truncatingUpstream;

    static {
        try {
            truncatingUpstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofVirtual().start(() -> {
            while (!truncatingUpstream.isClosed()) {
                try (Socket socket = truncatingUpstream.accept()) {
                    byte[] request = new byte[8192];
                    int length = socket.getInputStream().read(request);
                    OutputStream out = socket.getOutputStream();
                    if (!new String(request, 0, Math.max(0, length), StandardCharsets.US_ASCII).contains("/cut/")) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        continue;
                    }
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 102400\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.write(new byte[10240]);
                    out.flush();
                    Thread.sleep(300);
                } catch (Exception ignored) {
                    // next connection
                }
            }
        });
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.upstreams", () -> upstream.baseUrl() + "/maven2,http://127.0.0.1:"
                + truncatingUpstream.getLocalPort() + "/maven2");
        registry.add("localrepo.cache-dir", cacheDir::toString);
        registry.add("localrepo.negative-cache-ttl", () -> "0s");
    }

    @LocalServerPort
    int port;

    @Autowired
    DownloadTracker downloads;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyCacheAndStubUpstream() throws IOException {
        try (Stream<Path> files = Files.walk(cacheDir)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(cacheDir)).forEach(p -> p.toFile().delete());
        }
        upstream.stubFor(any(anyUrl()).willReturn(notFound()));
        upstream.stubFor(get(POM).willReturn(ok("<project/>")
                .withHeader("ETag", "\"v1\"")
                .withHeader("Last-Modified", "Tue, 06 Oct 2026 10:00:00 GMT")));
        upstream.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        upstream.stubFor(get(BIG).willReturn(ok().withBody(BIG_BODY).withChunkedDribbleDelay(20, 2000)));
    }

    @AfterEach
    void waitForBackgroundDownloads() throws InterruptedException {
        while (!downloads.active().isEmpty()) {
            Thread.sleep(20);
        }
    }

    @Test
    void servesAnArtifactFetchedFromUpstream() throws Exception {
        HttpResponse<String> response = http.send(request("junit/junit/4.13.2/junit-4.13.2.pom").build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("<project/>", response.body());
        assertEquals("application/xml", header(response, "Content-Type"));
        assertEquals("10", header(response, "Content-Length"));
        assertEquals("\"v1\"", header(response, "ETag"));
        assertEquals("Tue, 06 Oct 2026 10:00:00 GMT", header(response, "Last-Modified"));
    }

    @Test
    void servesTheSameHeadersFromTheCache() throws Exception {
        fetch("junit/junit/4.13.2/junit-4.13.2.pom");

        HttpResponse<byte[]> cached = fetch("junit/junit/4.13.2/junit-4.13.2.pom");

        assertEquals("application/xml", header(cached, "Content-Type"));
        assertEquals("10", header(cached, "Content-Length"));
        assertEquals("\"v1\"", header(cached, "ETag"));
        upstream.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void servesBinaryArtifactsAsJavaArchives() throws Exception {
        HttpResponse<byte[]> response = fetch("junit/junit/4.13.2/junit-4.13.2.jar");

        assertEquals("application/java-archive", header(response, "Content-Type"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, response.body());
    }

    @Test
    void answersHeadWithHeadersOnly() throws Exception {
        for (int i = 0; i < 2; i++) {
            HttpResponse<byte[]> response = http.send(request("junit/junit/4.13.2/junit-4.13.2.pom")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("10", header(response, "Content-Length"));
            assertEquals(0, response.body().length);
        }
    }

    @Test
    void returnsNotFoundWhenNoUpstreamHasTheArtifact() throws Exception {
        assertEquals(404, fetch("no/such/1/such-1.pom").statusCode());
        assertEquals(404, http.send(request("no/such/1/such-1.pom").method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        assertEquals(400, fetch("a/%2e%2e/%2e%2e/etc/passwd").statusCode());

        upstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void servesARangeOfACachedArtifact() throws Exception {
        fetch("junit/junit/4.13.2/junit-4.13.2.jar");

        HttpResponse<byte[]> response = http.send(request("junit/junit/4.13.2/junit-4.13.2.jar")
                .header("Range", "bytes=1-2").build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(206, response.statusCode());
        assertArrayEquals(new byte[]{2, 3}, response.body());
    }

    @Test
    void streamsTheFirstBytesBeforeTheUpstreamHasFinished() throws Exception {
        long start = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request("big/big/1/big-1.aar").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            assertNotEquals(-1, body.read());
            long firstByteMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(firstByteMillis < 1500, "first byte took " + firstByteMillis + "ms");
            assertFalse(Files.exists(cacheDir.resolve("default/big/big/1/big-1.aar")), "already complete");

            byte[] rest = body.readAllBytes();
            assertEquals(BIG_BODY.length - 1, rest.length);
            assertArrayEquals(Arrays.copyOfRange(BIG_BODY, 1, BIG_BODY.length), rest);
        }
    }

    @Test
    void sharesOneUpstreamFetchBetweenConcurrentRequests() throws Exception {
        List<CompletableFuture<HttpResponse<byte[]>>> responses = Stream.generate(() ->
                        http.sendAsync(request("big/big/1/big-1.aar").build(), HttpResponse.BodyHandlers.ofByteArray()))
                .limit(4)
                .toList();

        for (CompletableFuture<HttpResponse<byte[]>> response : responses) {
            assertArrayEquals(BIG_BODY, response.get(10, TimeUnit.SECONDS).body());
        }
        upstream.verify(1, getRequestedFor(urlEqualTo(BIG)));
    }

    @Test
    void finishesCachingWhenTheClientHangsUp() throws Exception {
        HttpResponse<InputStream> response = http.send(request("big/big/1/big-1.aar").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            body.read();
        }

        Path cached = cacheDir.resolve("default/big/big/1/big-1.aar");
        for (int i = 0; i < 100 && !Files.exists(cached); i++) {
            Thread.sleep(100);
        }
        assertArrayEquals(BIG_BODY, Files.readAllBytes(cached));
    }

    @Test
    void breaksTheResponseWhenTheUpstreamBreaks() throws Exception {
        upstream.stubFor(get(JAR).willReturn(aResponse().withStatus(200).withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        try {
            HttpResponse<byte[]> response = fetch("junit/junit/4.13.2/junit-4.13.2.jar");
            assertNotEquals(200, response.statusCode(), "a broken download must not look like a complete one");
        } catch (IOException expected) {
            // the connection was aborted mid-body
        }
        assertFalse(Files.exists(cacheDir.resolve("default/junit/junit/4.13.2/junit-4.13.2.jar")));
    }

    @Test
    void neverPresentsATruncatedUpstreamBodyAsComplete() throws Exception {
        HttpResponse<InputStream> response = http.send(request("cut/cut/1/cut-1.jar").build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());
        assertEquals("102400", header(response, "Content-Length"));
        try (InputStream body = response.body()) {
            assertThrows(IOException.class, body::readAllBytes);
        }
        assertFalse(Files.exists(cacheDir.resolve("default/cut/cut/1/cut-1.jar")));
    }

    private HttpResponse<byte[]> fetch(String path) throws Exception {
        return http.send(request(path).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cache/" + path));
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
