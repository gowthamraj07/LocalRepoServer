package com.localrepo.server.artifact;

import com.localrepo.server.MutableClock;
import com.github.tomakehurst.wiremock.http.Fault;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactServiceTest {

    private static final String POM = "/maven2/junit/junit/4.13.2/junit-4.13.2.pom";
    private static final ArtifactPath PATH = ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom");
    private static final ArtifactPath ANDROIDX = ArtifactPath.of("androidx/core/core/1.13.1/core-1.13.1.pom");

    @RegisterExtension
    static WireMockExtension first = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    @RegisterExtension
    static WireMockExtension second = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path root;
    private static final String METADATA = "/maven2/junit/junit/maven-metadata.xml";
    private static final ArtifactPath METADATA_PATH = ArtifactPath.of("junit/junit/maven-metadata.xml");

    private final MutableClock clock = new MutableClock(Instant.now());
    private final OfflineMode offline = new OfflineMode(false);
    private ArtifactService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(List.of(repo("first", first.baseUrl() + "/maven2"),
                repo("second", second.baseUrl() + "/maven2/")));
    }

    @Test
    void downloadsAMissFromTheFirstUpstreamAndCachesItUnderThatRepository() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>").withHeader("ETag", "\"v1\"")));

        CachedArtifact artifact = service.resolveAndWait(PATH).orElseThrow();

        assertEquals(root.resolve("first").resolve(PATH.value()), artifact.file());
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
    void findsAnArtifactCachedByALaterRepositoryWithoutTouchingTheNetwork() throws Exception {
        first.stubFor(get(POM).willReturn(notFound()));
        second.stubFor(get(POM).willReturn(ok("<project/>")));
        service.resolveAndWait(PATH);
        first.resetRequests();

        CachedArtifact artifact = service.resolveAndWait(PATH).orElseThrow();

        assertEquals(root.resolve("second").resolve(PATH.value()), artifact.file());
        first.verify(0, anyRequestedFor(anyUrl()));
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
        ArtifactService withDeadHost = serviceWith(List.of(repo("first", first.baseUrl() + "/maven2"),
                repo("second", second.baseUrl() + "/maven2"), repo("dead", "http://127.0.0.1:1/maven2")));

        assertTrue(withDeadHost.resolveAndWait(PATH).isEmpty());
    }

    @Test
    void asksOnlyRepositoriesWhoseFiltersAcceptThePath() throws Exception {
        String androidx = "/maven2/" + ANDROIDX.value();
        first.stubFor(get(anyUrl()).willReturn(notFound()));
        second.stubFor(get(androidx).willReturn(ok("<project/>")));
        ArtifactService routed = serviceWith(List.of(
                repo("first", first.baseUrl() + "/maven2", List.of(), List.of("androidx/**")),
                repo("second", second.baseUrl() + "/maven2", List.of("androidx/**"), List.of())));

        assertTrue(routed.resolveAndWait(ANDROIDX).isPresent());
        first.verify(0, anyRequestedFor(anyUrl()));

        routed.resolveAndWait(PATH);
        second.verify(0, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void aNamedRepositoryAsksOnlyItsOwnUpstream() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>")));
        second.stubFor(get(POM).willReturn(ok("<project/>")));

        CachedArtifact artifact = service.resolveAndWait("second", PATH).orElseThrow();

        assertEquals(root.resolve("second").resolve(PATH.value()), artifact.file());
        first.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anUnknownRepositoryHasNothing() throws Exception {
        assertTrue(service.resolveAndWait("nope", PATH).isEmpty());
    }

    @Test
    void sendsTheRepositoryCredentials() throws Exception {
        first.stubFor(get(POM).withHeader("Authorization", equalTo("Bearer t0ken")).willReturn(ok("<project/>")));
        ArtifactService authenticated = serviceWith(List.of(new Repository("first", first.baseUrl() + "/maven2",
                List.of(), List.of(), Repository.Credentials.bearer("t0ken"), store("first"))));

        assertTrue(authenticated.resolveAndWait(PATH).isPresent());
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
    void aMissInTheGroupDoesNotHideTheArtifactFromANamedRepository() throws Exception {
        first.stubFor(get(POM).willReturn(notFound()));
        second.stubFor(get(POM).willReturn(notFound()));
        service.resolveAndWait(PATH);
        second.stubFor(get(POM).willReturn(ok("<project/>")));

        assertTrue(service.resolveAndWait("second", PATH).isPresent());
    }

    @Test
    void doesNotRememberAMissCausedByAnUpstreamError() throws Exception {
        first.stubFor(get(POM).willReturn(serverError()));
        second.stubFor(get(POM).willReturn(notFound()));

        service.resolveAndWait(PATH);
        long asked = first.countRequestsMatching(getRequestedFor(urlEqualTo(POM)).build()).getCount();
        service.resolveAndWait(PATH);

        first.verify((int) (2 * asked), getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void retriesAnUpstreamThatFailsOnce() throws Exception {
        first.stubFor(get(POM).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        first.stubFor(get(POM).inScenario("flaky").whenScenarioStateIs("recovered").willReturn(ok("<project/>")));

        assertTrue(service.resolveAndWait(PATH).isPresent());
    }

    @Test
    void reportsAFailureRatherThanAMissWhenAnUpstreamErrored() throws Exception {
        // A 404 would be remembered by Maven until its update interval passes; a failure is retried on the next build.
        first.stubFor(get(POM).willReturn(serverError()));
        second.stubFor(get(POM).willReturn(notFound()));

        Download download = ((ArtifactService.Resolution.Downloading) service.resolve(PATH)).download();

        assertEquals(Download.State.FAILED, download.awaitHeaders());
    }

    @Test
    void aClientErrorFromAnUpstreamStillCountsAsAMiss() throws Exception {
        // Repositories answer 401 or 403 for paths they do not have; the build should fall back to its own repositories.
        first.stubFor(get(POM).willReturn(aResponse().withStatus(401)));
        second.stubFor(get(POM).willReturn(notFound()));

        Download download = ((ArtifactService.Resolution.Downloading) service.resolve(PATH)).download();

        assertEquals(Download.State.NOT_FOUND, download.awaitHeaders());
    }

    @Test
    void doesNotCacheABodyThatBreaksHalfway() throws Exception {
        first.stubFor(get(POM).willReturn(aResponse().withStatus(200).withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        second.stubFor(get(POM).willReturn(notFound()));

        Optional<CachedArtifact> result = service.resolveAndWait(PATH);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(root.resolve("first").resolve(PATH.value())));
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
        ArtifactService impatient = serviceWith(List.of(repo("first", first.baseUrl() + "/maven2")),
                Duration.ofMillis(300));

        long start = System.nanoTime();
        assertTrue(impatient.resolveAndWait(PATH).isEmpty());

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        assertFalse(Files.exists(root.resolve("first").resolve(PATH.value())));
    }

    @Test
    void givesUpOnAnUpstreamThatNeverAnswers() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>").withFixedDelay(4000)));
        second.stubFor(get(POM).willReturn(ok("<project/>")));
        ArtifactService impatient = serviceWith(List.of(repo("first", first.baseUrl() + "/maven2"),
                repo("second", second.baseUrl() + "/maven2")), Duration.ofMillis(300));

        CachedArtifact artifact = impatient.resolveAndWait(PATH).orElseThrow();

        assertEquals(second.baseUrl() + POM, artifact.meta().upstreamUrl());
    }

    @Test
    void neverRevalidatesARelease() throws Exception {
        first.stubFor(get(POM).willReturn(ok("<project/>")));
        service.resolveAndWait(PATH);
        clock.advance(Duration.ofDays(365));

        service.resolveAndWait(PATH).orElseThrow();

        first.verify(1, getRequestedFor(urlEqualTo(POM)));
    }

    @Test
    void servesFreshMetadataFromTheCache() throws Exception {
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v1</metadata>")));
        service.resolveAndWait(METADATA_PATH);
        clock.advance(Duration.ofHours(23));

        service.resolveAndWait(METADATA_PATH).orElseThrow();

        first.verify(1, getRequestedFor(urlEqualTo(METADATA)));
    }

    @Test
    void revalidatesStaleMetadataAndKeepsItWhenUnchanged() throws Exception {
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v1</metadata>").withHeader("ETag", "\"m1\"")));
        service.resolveAndWait(METADATA_PATH);
        first.stubFor(get(METADATA).withHeader("If-None-Match", equalTo("\"m1\"")).willReturn(status(304)));
        clock.advance(Duration.ofHours(25));

        CachedArtifact revalidated = service.resolveAndWait(METADATA_PATH).orElseThrow();
        service.resolveAndWait(METADATA_PATH);

        assertEquals("<metadata>v1</metadata>", Files.readString(revalidated.file()));
        assertEquals(clock.instant(), revalidated.meta().fetchedAt());
        first.verify(2, getRequestedFor(urlEqualTo(METADATA)));
    }

    @Test
    void replacesStaleMetadataThatChanged() throws Exception {
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v1</metadata>")));
        service.resolveAndWait(METADATA_PATH);
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v2</metadata>")));
        clock.advance(Duration.ofHours(25));

        CachedArtifact refreshed = service.resolveAndWait(METADATA_PATH).orElseThrow();

        assertEquals("<metadata>v2</metadata>", Files.readString(refreshed.file()));
    }

    @Test
    void servesStaleMetadataWhenTheUpstreamIsDown() throws Exception {
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v1</metadata>")));
        service.resolveAndWait(METADATA_PATH);
        first.stubFor(get(METADATA).willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)));
        clock.advance(Duration.ofHours(25));

        ArtifactService.Resolution resolution = service.resolve(METADATA_PATH);
        Download download = ((ArtifactService.Resolution.Downloading) resolution).download();
        CachedArtifact stale = download.awaitResult().orElseThrow();

        assertEquals("<metadata>v1</metadata>", Files.readString(stale.file()));
        assertTrue(download.isStale());
    }

    @Test
    void offlineServesWhatIsCachedEvenIfStaleAndNeverTouchesTheNetwork() throws Exception {
        first.stubFor(get(METADATA).willReturn(ok("<metadata>v1</metadata>")));
        service.resolveAndWait(METADATA_PATH);
        clock.advance(Duration.ofHours(25));
        first.resetRequests();
        offline.set(true);

        ArtifactService.Resolution metadata = service.resolve(METADATA_PATH);
        ArtifactService.Resolution miss = service.resolve(PATH);

        assertTrue(metadata instanceof ArtifactService.Resolution.Cached cached && cached.stale());
        assertInstanceOf(ArtifactService.Resolution.Missing.class, miss);
        first.verify(0, anyRequestedFor(anyUrl()));
        second.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void goingBackOnlineFetchesMissesAgain() throws Exception {
        offline.set(true);
        service.resolveAndWait(PATH);
        offline.set(false);
        first.stubFor(get(POM).willReturn(ok("<project/>")));

        assertTrue(service.resolveAndWait(PATH).isPresent());
    }

    private static final String JAR = "/maven2/junit/junit/4.13.2/junit-4.13.2.jar";
    private static final ArtifactPath JAR_PATH = ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.jar");
    // sha256 and sha1 of the four bytes 1, 2, 3, 4
    private static final String JAR_SHA256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a";
    private static final String JAR_SHA1 = "12dada1fff4d4787ade3333147202c3b443e376f";

    @Test
    void verifiesADownloadAgainstTheUpstreamSha256AndCachesTheChecksum() throws Exception {
        first.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        first.stubFor(get(JAR + ".sha256").willReturn(ok(JAR_SHA256)));

        CachedArtifact artifact = service.resolveAndWait(JAR_PATH).orElseThrow();

        assertEquals("sha256", artifact.meta().checksumVerified());
        assertEquals(JAR_SHA1, artifact.meta().sha1());
        assertEquals(JAR_SHA256, Files.readString(root.resolve("first").resolve(JAR_PATH.value() + ".sha256")));
        first.verify(0, getRequestedFor(urlEqualTo(JAR + ".sha1")));
    }

    @Test
    void fallsBackToSha1AndAcceptsTheChecksumFileFormatWithAFileName() throws Exception {
        first.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        first.stubFor(get(JAR + ".sha1").willReturn(ok(JAR_SHA1.toUpperCase() + "  junit-4.13.2.jar\n")));

        CachedArtifact artifact = service.resolveAndWait(JAR_PATH).orElseThrow();

        assertEquals("sha1", artifact.meta().checksumVerified());
    }

    @Test
    void rejectsAndQuarantinesADownloadThatDoesNotMatchItsChecksum() throws Exception {
        first.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 5})));
        first.stubFor(get(JAR + ".sha1").willReturn(ok(JAR_SHA1)));

        assertTrue(service.resolveAndWait(JAR_PATH).isEmpty());

        assertFalse(Files.exists(root.resolve("first").resolve(JAR_PATH.value())));
        try (var quarantined = Files.walk(root.resolve(".quarantine"))) {
            assertEquals(1, quarantined.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void acceptsADownloadWhenTheUpstreamPublishesNoChecksum() throws Exception {
        first.stubFor(get(JAR).willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));

        CachedArtifact artifact = service.resolveAndWait(JAR_PATH).orElseThrow();

        assertNull(artifact.meta().checksumVerified());
        assertEquals(JAR_SHA256, artifact.meta().sha256());
    }

    @Test
    void doesNotLookForChecksumsOfChecksums() throws Exception {
        first.stubFor(get(JAR + ".sha1").willReturn(ok(JAR_SHA1)));

        service.resolveAndWait(ArtifactPath.of(JAR_PATH.value() + ".sha1")).orElseThrow();

        first.verify(0, getRequestedFor(urlEqualTo(JAR + ".sha1.sha256")));
        first.verify(0, getRequestedFor(urlEqualTo(JAR + ".sha1.sha1")));
    }

    @Test
    void addsAndRemovesRepositoriesWhileRunning() throws Exception {
        first.stubFor(get(POM).willReturn(notFound()));
        second.stubFor(get(POM).willReturn(notFound()));
        service.addRepository(repo("third", second.baseUrl() + "/other"));
        second.stubFor(get("/other/junit/junit/4.13.2/junit-4.13.2.pom").willReturn(ok("<project/>")));

        assertEquals(List.of("first", "second", "third"), service.repositories().stream().map(Repository::name).toList());
        assertTrue(service.resolveAndWait(PATH).isPresent());

        service.removeRepository("third");
        assertEquals(List.of("first", "second"), service.repositories().stream().map(Repository::name).toList());
        assertThrows(IllegalArgumentException.class, () -> service.addRepository(repo("first", "http://x")));
    }

    private Repository repo(String name, String url) {
        return repo(name, url, List.of(), List.of());
    }

    private Repository repo(String name, String url, List<String> includes, List<String> excludes) {
        return new Repository(name, url, includes, excludes, Repository.Credentials.NONE, store(name));
    }

    private ArtifactStore store(String name) {
        return new ArtifactStore(root.resolve(name), clock);
    }

    private ArtifactService serviceWith(List<Repository> repositories) {
        return serviceWith(repositories, Duration.ofSeconds(5));
    }

    private ArtifactService serviceWith(List<Repository> repositories, Duration idleTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new ArtifactService(repositories, new UpstreamClient(http, idleTimeout, Duration.ZERO),
                new NegativeCache(Duration.ofMinutes(5), clock),
                new DownloadCoordinator(Clock.systemUTC(), idleTimeout, new DownloadTracker(clock)),
                new FreshnessPolicy(Duration.ofHours(24), clock), offline);
    }
}
