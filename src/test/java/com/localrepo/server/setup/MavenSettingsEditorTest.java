package com.localrepo.server.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MavenSettingsEditorTest {

    private static final String URL = "http://127.0.0.1:8082/cache";
    private final MavenSettingsEditor editor = new MavenSettingsEditor();

    private static final String WITH_MIRRORS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
              <!-- my comment -->
              <mirrors>
                <mirror>
                  <id>company</id>
                  <mirrorOf>company-releases</mirrorOf>
                  <url>https://nexus.example/releases</url>
                </mirror>
              </mirrors>
              <servers><server><id>company</id><username>me</username></server></servers>
            </settings>
            """;

    private static final String WITHOUT_MIRRORS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings>
              <localRepository>/data/m2</localRepository>
            </settings>
            """;

    @Test
    void createsSettingsWhenThereAreNone() {
        String settings = editor.install(null, URL);

        assertTrue(settings.contains("<mirrorOf>*</mirrorOf>"));
        assertTrue(settings.contains("<url>" + URL + "</url>"));
        assertTrue(editor.isInstalled(settings, URL));
        assertNull(editor.uninstall(settings), "a file we created is removed again");
    }

    @Test
    void addsTheMirrorFirstAndKeepsEverythingElse() {
        String settings = editor.install(WITH_MIRRORS, URL);

        assertTrue(settings.indexOf("<id>localrepo</id>") < settings.indexOf("<id>company</id>"));
        assertTrue(settings.contains("<!-- my comment -->"));
        assertTrue(settings.contains("<username>me</username>"));
        assertEquals(WITH_MIRRORS, editor.uninstall(settings));
    }

    @Test
    void addsAMirrorsSectionWhenThereIsNone() {
        String settings = editor.install(WITHOUT_MIRRORS, URL);

        assertTrue(settings.contains("<mirrors>"));
        assertTrue(settings.contains("<localRepository>/data/m2</localRepository>"));
        assertEquals(WITHOUT_MIRRORS, editor.uninstall(settings));
    }

    @Test
    void reinstallingReplacesTheMirrorInsteadOfAddingAnother() {
        String once = editor.install(WITH_MIRRORS, "http://127.0.0.1:1111/cache");
        String twice = editor.install(once, URL);

        assertEquals(1, twice.split("<id>localrepo</id>", -1).length - 1);
        assertTrue(editor.isInstalled(twice, URL));
        assertFalse(editor.isInstalled(twice, "http://127.0.0.1:1111/cache"));
        assertEquals(WITH_MIRRORS, editor.uninstall(twice));
    }

    @Test
    void uninstallingSettingsWithoutTheMirrorChangesNothing() {
        assertEquals(WITH_MIRRORS, editor.uninstall(WITH_MIRRORS));
        assertFalse(editor.isInstalled(WITH_MIRRORS, URL));
    }

    @Test
    void refusesToEditSettingsThatAreNotValidXml() {
        assertThrows(IllegalArgumentException.class, () -> editor.install("<settings><mirrors></settings>", URL));
        assertThrows(IllegalArgumentException.class, () -> editor.install("not xml", URL));
    }

    @Test
    void handlesASelfClosingMirrorsElement() {
        String original = "<settings>\n  <mirrors/>\n</settings>\n";

        String settings = editor.install(original, URL);

        assertTrue(editor.isInstalled(settings, URL));
        assertEquals(original, editor.uninstall(settings));
    }
}
