package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.DownloadCoordinator;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.FreshnessPolicy;
import com.localrepo.server.artifact.NegativeCache;
import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.artifact.Repository;
import com.localrepo.server.artifact.UpstreamClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamEditorTest {

    @TempDir
    Path home;
    private ArtifactService service;
    private UpstreamEditor editor;
    private RepositoryFactory factory;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        LocalRepoProperties properties = new LocalRepoProperties(home, null, null, home.resolve("cache"), null, null,
                null, null, null, null, false, null, null, null);
        factory = new RepositoryFactory(properties, new MockEnvironment(), clock);
        service = new ArtifactService(List.of(factory.create(upstream("central", "https://repo.example/maven2"))),
                new UpstreamClient(HttpClient.newHttpClient(), Duration.ofSeconds(1)), new NegativeCache(Duration.ZERO, clock),
                new DownloadCoordinator(clock, Duration.ofSeconds(1), new DownloadTracker(clock)),
                new FreshnessPolicy(Duration.ofHours(1), clock), new OfflineMode(false));
        editor = new UpstreamEditor(home.resolve("upstreams.yml"), List.of(), service, factory);
    }

    @Test
    void addsAnUpstreamLiveAndSavesItForTheNextStart() throws Exception {
        editor.add(new LocalRepoProperties.Upstream("company", "https://nexus.example/releases", List.of("com/example/**"),
                List.of(), new LocalRepoProperties.Credentials(null, null, null, "nexus.user", "nexus.password", null)));

        assertEquals(List.of("central", "company"), service.repositories().stream().map(Repository::name).toList());
        Map<String, Object> saved = new Yaml().load(Files.readString(home.resolve("upstreams.yml")));
        @SuppressWarnings("unchecked")
        Map<String, Object> company = ((List<Map<String, Object>>) ((Map<String, Object>) saved.get("localrepo"))
                .get("extra-upstreams")).getFirst();
        assertEquals("company", company.get("name"));
        assertEquals("https://nexus.example/releases", company.get("url"));
        assertEquals(List.of("com/example/**"), company.get("includes"));
        assertEquals(Map.of("gradle-property-username", "nexus.user", "gradle-property-password", "nexus.password"),
                company.get("credentials"));
        assertEquals(1, editor.extras().size());
    }

    @Test
    void removesOnlyUpstreamsItAdded() throws Exception {
        editor.add(upstream("company", "https://nexus.example/releases"));

        editor.remove("company");

        assertEquals(List.of("central"), service.repositories().stream().map(Repository::name).toList());
        assertTrue(editor.extras().isEmpty());
        assertFalse(Files.readString(home.resolve("upstreams.yml")).contains("company"));
        assertThrows(IllegalArgumentException.class, () -> editor.remove("central"));
    }

    @Test
    void rejectsBadUpstreams() {
        assertThrows(IllegalArgumentException.class, () -> editor.add(upstream("central", "https://other.example")));
        assertThrows(IllegalArgumentException.class, () -> editor.add(upstream("Bad Name", "https://other.example")));
        assertThrows(IllegalArgumentException.class, () -> editor.add(upstream("ftp", "ftp://other.example")));
        assertFalse(Files.exists(home.resolve("upstreams.yml")));
    }

    private static LocalRepoProperties.Upstream upstream(String name, String url) {
        return new LocalRepoProperties.Upstream(name, url, null, null, null);
    }
}
