package com.localrepo.server.ui;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.OfflineMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UiControllerTest {

    private static final String ACTION = "X-LocalRepo-Action";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.upstreams[0].name", () -> "mock");
        registry.add("localrepo.upstreams[0].url", () -> upstream.baseUrl() + "/maven2");
        registry.add("localrepo.cache-dir", () -> home.resolve("cache").toString());
        registry.add("localrepo.gradle-user-home", () -> home.resolve("gradle").toString());
        registry.add("localrepo.maven-settings", () -> home.resolve("m2/settings.xml").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ArtifactService service;

    @Autowired
    OfflineMode offline;

    @BeforeEach
    void stubUpstream() {
        upstream.stubFor(any(anyUrl()).willReturn(notFound()));
        upstream.stubFor(WireMock.get("/maven2/junit/junit/4.13.2/junit-4.13.2.jar")
                .willReturn(ok().withBody(new byte[]{1, 2, 3, 4})));
        upstream.stubFor(WireMock.get("/maven2/").willReturn(ok("index")));
    }

    @Test
    void rendersEveryPageWithNavigationAndTheOfflineSwitch() throws Exception {
        for (String page : new String[]{"/", "/downloads", "/artifacts", "/upstreams", "/setup", "/maintenance"}) {
            mvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("LocalRepoServer")))
                    .andExpect(content().string(containsString("href=\"/artifacts\"")))
                    .andExpect(content().string(containsString("/vendor/htmx-2.0.11.min.js")))
                    .andExpect(content().string(containsString("id=\"offline-switch\"")));
        }
    }

    @Test
    void dashboardShowsTheStatistics() throws Exception {
        mvc.perform(get("/ui/fragments/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hit rate")))
                .andExpect(content().string(containsString("Saved")))
                .andExpect(content().string(containsString("Disk use")));
    }

    @Test
    void artifactsFragmentListsAndSearchesCachedFiles() throws Exception {
        service.resolveAndWait(ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.jar")).orElseThrow();

        mvc.perform(get("/ui/fragments/artifacts").param("q", "junit-4.13"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("junit-4.13.2.jar")))
                .andExpect(content().string(containsString("4.13.2")))
                .andExpect(content().string(containsString("Refetch")));
        mvc.perform(get("/ui/fragments/artifacts").param("q", "no-such-thing"))
                .andExpect(content().string(not(containsString("junit-4.13.2.jar"))))
                .andExpect(content().string(containsString("Nothing cached")));
    }

    @Test
    void downloadsFragmentShowsRecentDownloads() throws Exception {
        service.resolveAndWait(ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.jar"));

        mvc.perform(get("/ui/fragments/downloads"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Recent")));
    }

    @Test
    void upstreamsPageShowsRepositoriesAndTheirReachability() throws Exception {
        mvc.perform(get("/upstreams"))
                .andExpect(content().string(containsString("mock")))
                .andExpect(content().string(containsString(upstream.baseUrl() + "/maven2")));
        mvc.perform(get("/ui/fragments/upstream-status").param("name", "mock"))
                .andExpect(content().string(containsString("Reachable")));
    }

    @Test
    void setupPageInstallsAndUninstallsTheGradleInitScript() throws Exception {
        mvc.perform(get("/setup")).andExpect(content().string(containsString("localrepo.init.gradle")));

        mvc.perform(post("/ui/setup/gradle/install")).andExpect(status().isForbidden());
        mvc.perform(post("/ui/setup/gradle/install").header(ACTION, "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Installed")));
        assertTrue(Files.exists(home.resolve("gradle/init.d/localrepo.init.gradle")));

        mvc.perform(post("/ui/setup/gradle/uninstall").header(ACTION, "true"))
                .andExpect(content().string(containsString("Not installed")));
        assertFalse(Files.exists(home.resolve("gradle/init.d/localrepo.init.gradle")));
    }

    @Test
    void setupPageInstallsTheMavenMirror() throws Exception {
        mvc.perform(post("/ui/setup/maven/install").header(ACTION, "true"))
                .andExpect(content().string(containsString("Installed")));
        assertTrue(Files.readString(home.resolve("m2/settings.xml")).contains("<mirrorOf>*</mirrorOf>"));
        mvc.perform(post("/ui/setup/maven/uninstall").header(ACTION, "true"));
    }

    @Test
    void maintenancePageOffersExportImportPrefetchAndPurge() throws Exception {
        mvc.perform(get("/maintenance"))
                .andExpect(content().string(containsString("/api/export")))
                .andExpect(content().string(containsString("Prefetch")))
                .andExpect(content().string(containsString("No size limit")));
    }

    @Test
    void importsAnUploadedBundle() throws Exception {
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(zip)) {
            out.putNextEntry(new java.util.zip.ZipEntry("mock/x/y/1/y-1.jar"));
            out.write(new byte[]{7, 7});
            out.closeEntry();
        }
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "bundle.zip", "application/zip", zip.toByteArray());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/ui/import").file(file))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/ui/import").file(file)
                        .header(ACTION, "true"))
                .andExpect(content().string(containsString("Imported 1")));
        assertTrue(Files.exists(home.resolve("cache/mock/x/y/1/y-1.jar")));
    }

    @Test
    void prefetchesAndPurgesFromTheUi() throws Exception {
        mvc.perform(post("/ui/prefetch").header(ACTION, "true").param("list", "junit/junit/4.13.2/junit-4.13.2.jar"))
                .andExpect(content().string(containsString("of 1")));
        while (Files.notExists(home.resolve("cache/mock/junit/junit/4.13.2/junit-4.13.2.jar"))) {
            Thread.sleep(10);
        }
        mvc.perform(post("/ui/prefetch").header(ACTION, "true").param("list", "only:two"))
                .andExpect(content().string(containsString("Expected group:artifact:version")));

        mvc.perform(post("/ui/purge").header(ACTION, "true").param("path", "junit"))
                .andExpect(content().string(containsString("Deleted")));
        assertFalse(Files.exists(home.resolve("cache/mock/junit/junit/4.13.2/junit-4.13.2.jar")));
    }

    @Test
    void togglesOfflineMode() throws Exception {
        mvc.perform(post("/ui/offline")).andExpect(status().isForbidden());

        mvc.perform(post("/ui/offline").header(ACTION, "true"))
                .andExpect(content().string(containsString("Offline")));
        assertTrue(offline.isEnabled());

        mvc.perform(post("/ui/offline").header(ACTION, "true"))
                .andExpect(content().string(containsString("Online")));
        assertFalse(offline.isEnabled());
    }
}
