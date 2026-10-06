package com.localrepo.server.artifact;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.stream.Stream;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ArtifactControllerTest {

    private static final String POM = "/maven2/junit/junit/4.13.2/junit-4.13.2.pom";
    private static final String JAR = "/maven2/junit/junit/4.13.2/junit-4.13.2.jar";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    static Path cacheDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.upstreams", () -> upstream.baseUrl() + "/maven2");
        registry.add("localrepo.cache-dir", cacheDir::toString);
        registry.add("localrepo.negative-cache-ttl", () -> "0s");
    }

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @BeforeEach
    void emptyCacheAndStubUpstream() throws IOException {
        try (Stream<Path> files = Files.walk(cacheDir)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(cacheDir)).forEach(p -> p.toFile().delete());
        }
        upstream.stubFor(any(anyUrl()).willReturn(notFound()));
        upstream.stubFor(WireMock.get(POM).willReturn(ok("<project/>")
                .withHeader("ETag", "\"v1\"")
                .withHeader("Last-Modified", "Tue, 06 Oct 2026 10:00:00 GMT")));
        upstream.stubFor(WireMock.get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
    }

    @Test
    void servesAnArtifactFetchedFromUpstream() throws Exception {
        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.pom"))
                .andExpect(status().isOk())
                .andExpect(content().string("<project/>"))
                .andExpect(header().string("Content-Type", "application/xml"))
                .andExpect(header().longValue("Content-Length", 10))
                .andExpect(header().string("ETag", "\"v1\""))
                .andExpect(header().string("Last-Modified", "Tue, 06 Oct 2026 10:00:00 GMT"));
    }

    @Test
    void servesBinaryArtifactsAsJavaArchives() throws Exception {
        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.jar"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/java-archive"))
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void servesTheSecondRequestFromTheCache() throws Exception {
        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.pom")).andExpect(status().isOk());
        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.pom")).andExpect(status().isOk());

        upstream.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void answersHeadWithHeadersOnly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/cache/junit/junit/4.13.2/junit-4.13.2.pom"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertEquals("10", response.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(0, response.body().length);
    }

    @Test
    void returnsNotFoundWhenNoUpstreamHasTheArtifact() throws Exception {
        mvc.perform(get("/cache/no/such/1/such-1.pom")).andExpect(status().isNotFound());
        mvc.perform(head("/cache/no/such/1/such-1.pom")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        mvc.perform(get(URI.create("/cache/a/%2e%2e/%2e%2e/etc/passwd"))).andExpect(status().isBadRequest());

        upstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void servesARangeOfACachedArtifact() throws Exception {
        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.jar")).andExpect(status().isOk());

        mvc.perform(get("/cache/junit/junit/4.13.2/junit-4.13.2.jar").header("Range", "bytes=1-2"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(new byte[]{2, 3}));
    }
}
