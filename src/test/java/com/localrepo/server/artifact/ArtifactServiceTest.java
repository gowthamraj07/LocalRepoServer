package com.localrepo.server.artifact;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactServiceTest {

    private static final String POM = "/maven2/junit/junit/4.13.2/junit-4.13.2.pom";
    private static final ArtifactPath PATH = ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom");

    @RegisterExtension
    static WireMockExtension first = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    @RegisterExtension
    static WireMockExtension second = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path root;
    private ArtifactService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(List.of(first.baseUrl() + "/maven2", second.baseUrl() + "/maven2/"));
    }

    @Test
    void downloadsAMissFromTheFirstUpstreamAndCachesIt() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>").withHeader("ETag", "\"v1\"")));

        CachedArtifact artifact = service.resolveAndWait(PATH).orElseThrow();

        assertEquals("<project/>", Files.readString(artifact.file()));
        assertEquals(first.baseUrl() + POM, artifact.meta().upstreamUrl());
        assertEquals("\"v1\"", artifact.meta().etag());
        second.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void servesACachedArtifactWithoutTouchingTheNetwork() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>")));
        service.resolveAndWait(PATH);

        service.resolveAndWait(PATH).orElseThrow();

        first.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void fallsThroughToTheNextUpstreamOnNotFound() throws Exception {
        first.stubFor(get(POM).willReturn(notFound()));
        second.stubFor(get(POM).willReturn(ok("<project/>")));

        CachedArtifact artifact = service.resolveAndWait(PATH).orElseThrow();

        assertEquals(second.baseUrl() + POM, artifact.meta().upstreamUrl());
    }

    @Test
    void fallsThroughToTheNextUpstreamOnServerErrorOrBrokenConnection() throws Exception {
        first.stubFor(get(POM).willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)));
        second.stubFor(get(POM).willReturn(serverError()));
        ArtifactService withDeadHost = serviceWith(List.of(first.baseUrl() + "/maven2", second.baseUrl() + "/maven2",
                "http://127.0.0.1:1/maven2"));

        assertTrue(withDeadHost.resolveAndWait(PATH).isEmpty());
    }

    @Test
    void remembersAMissWhenEveryUpstreamSaysNotFound() throws Exception {
        first.stubFor(get(POM).willReturn(notFound()));
        second.stubFor(get(POM).willReturn(notFound()));

        assertTrue(service.resolveAndWait(PATH).isEmpty());
        assertTrue(service.resolveAndWait(PATH).isEmpty());

        first.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void doesNotRememberAMissCausedByAnUpstreamError() throws Exception {
        first.stubFor(get(POM).willReturn(serverError()));
        second.stubFor(get(POM).willReturn(notFound()));

        service.resolveAndWait(PATH);
        service.resolveAndWait(PATH);

        first.verify(2, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void doesNotCacheABodyThatBreaksHalfway() throws Exception {
        first.stubFor(get(POM).willReturn(aResponse().withStatus(200).withFault(
                com.github.tomakehurst.wiremock.http.Fault.MALFORMED_RESPONSE_CHUNK)));
        second.stubFor(get(POM).willReturn(notFound()));

        Optional<CachedArtifact> result = service.resolveAndWait(PATH);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(root.resolve(PATH.value())));
    }

    @Test
    void fetchesAnArtifactOnceForConcurrentRequests() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>").withChunkedDribbleDelay(5, 500)));

        List<CompletableFuture<Optional<CachedArtifact>>> requests = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return service.resolveAndWait(PATH);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }))
                .toList();

        for (CompletableFuture<Optional<CachedArtifact>> request : requests) {
            assertTrue(request.get(5, TimeUnit.SECONDS).isPresent());
        }
        first.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void givesUpOnAnUpstreamThatStallsMidBody() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project>" + "x".repeat(1000) + "</project>")
                .withChunkedDribbleDelay(2, 4000)));
        ArtifactService impatient = serviceWith(List.of(first.baseUrl() + "/maven2"), Duration.ofMillis(300));

        long start = System.nanoTime();
        assertTrue(impatient.resolveAndWait(PATH).isEmpty());

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        assertFalse(Files.exists(root.resolve(PATH.value())));
    }

    @Test
    void givesUpOnAnUpstreamThatNeverAnswers() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>").withFixedDelay(4000)));
        second.stubFor(get(POM).willReturn(ok("<project/>")));
        ArtifactService impatient = serviceWith(List.of(first.baseUrl() + "/maven2", second.baseUrl() + "/maven2"),
                Duration.ofMillis(300));

        CachedArtifact artifact = impatient.resolveAndWait(PATH).orElseThrow();

        assertEquals(second.baseUrl() + POM, artifact.meta().upstreamUrl());
    }

    private ArtifactService serviceWith(List<String> upstreams) {
        return serviceWith(upstreams, Duration.ofSeconds(5));
    }

    private ArtifactService serviceWith(List<String> upstreams, Duration idleTimeout) {
        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new ArtifactService(new ArtifactStore(root, clock), new UpstreamClient(http, idleTimeout), upstreams,
                new NegativeCache(Duration.ofMinutes(5), clock),
                new DownloadCoordinator(clock, idleTimeout, new DownloadTracker(clock)));
    }
}
