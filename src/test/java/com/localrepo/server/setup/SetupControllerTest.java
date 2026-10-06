package com.localrepo.server.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupControllerTest {

    @TempDir
    static Path gradleUserHome;

    @TempDir
    static Path m2;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("localrepo.gradle-user-home", gradleUserHome::toString);
        registry.add("localrepo.maven-settings", () -> m2.resolve("settings.xml").toString());
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void installsReportsAndUninstallsTheGradleInitScript() throws Exception {
        Path installed = gradleUserHome.resolve("init.d/localrepo.init.gradle");

        assertEquals(200, post("/setup/gradle/install", true).statusCode());
        assertEquals(get("/setup/gradle/localrepo.init.gradle").body(), Files.readString(installed));
        String status = get("/setup/gradle").body();
        assertTrue(status.contains("\"installed\":true"), status);
        assertTrue(status.contains("\"current\":true"), status);

        assertEquals(200, post("/setup/gradle/uninstall", true).statusCode());
        assertFalse(Files.exists(installed));
        assertTrue(get("/setup/gradle").body().contains("\"installed\":false"));
    }

    @Test
    void reportsAnOutdatedScript() throws Exception {
        post("/setup/gradle/install", true);
        Files.writeString(gradleUserHome.resolve("init.d/localrepo.init.gradle"), "// old version");

        assertTrue(get("/setup/gradle").body().contains("\"current\":false"));
        post("/setup/gradle/uninstall", true);
    }

    @Test
    void refusesChangesWithoutTheActionHeaderSoOtherSitesCannotTriggerThem() throws Exception {
        assertEquals(403, post("/setup/gradle/install", false).statusCode());

        assertFalse(Files.exists(gradleUserHome.resolve("init.d/localrepo.init.gradle")));
    }

    @Test
    void servesMavenSettingsWithAMirrorToThisServer() throws Exception {
        String settings = get("/setup/maven/settings.xml").body();

        assertTrue(settings.contains("<url>http://127.0.0.1:" + port + "/cache</url>"), settings);
        assertTrue(settings.contains("<mirrorOf>*</mirrorOf>"));
    }

    @Test
    void installsIntoExistingMavenSettingsWithABackupAndRestoresThemOnUninstall() throws Exception {
        try (Stream<Path> old = Files.list(m2)) {
            old.forEach(p -> p.toFile().delete());
        }
        Path settings = m2.resolve("settings.xml");
        String original = "<settings>\n  <localRepository>/data/m2</localRepository>\n</settings>\n";
        Files.writeString(settings, original);

        assertEquals(200, post("/setup/maven/install", true).statusCode());
        assertTrue(Files.readString(settings).contains("<url>http://127.0.0.1:" + port + "/cache</url>"));
        assertTrue(get("/setup/maven").body().contains("\"installed\":true"));
        try (Stream<Path> files = Files.list(m2)) {
            Path backup = files.filter(p -> p.getFileName().toString().startsWith("settings.xml.localrepo-bak-"))
                    .findFirst().orElseThrow();
            assertEquals(original, Files.readString(backup));
        }

        assertEquals(200, post("/setup/maven/uninstall", true).statusCode());
        assertEquals(original, Files.readString(settings));
        assertTrue(get("/setup/maven").body().contains("\"installed\":false"));
    }

    @Test
    void createsAndRemovesMavenSettingsWhenThereWereNone() throws Exception {
        Path settings = m2.resolve("settings.xml");
        Files.deleteIfExists(settings);

        post("/setup/maven/install", true);
        assertTrue(Files.exists(settings));

        post("/setup/maven/uninstall", true);
        assertFalse(Files.exists(settings));
    }

    @Test
    void refusesToTouchInvalidMavenSettings() throws Exception {
        Path settings = m2.resolve("settings.xml");
        Files.writeString(settings, "<settings><oops></settings>");

        HttpResponse<String> response = post("/setup/maven/install", true);

        assertEquals(400, response.statusCode());
        assertEquals("<settings><oops></settings>", Files.readString(settings));
        Files.delete(settings);
    }

    @Test
    void refusesMavenChangesWithoutTheActionHeader() throws Exception {
        assertEquals(403, post("/setup/maven/install", false).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, boolean withActionHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
        if (withActionHeader) {
            request.header(SetupController.ACTION_HEADER, "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
