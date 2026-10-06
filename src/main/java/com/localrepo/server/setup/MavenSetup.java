package com.localrepo.server.setup;

import com.localrepo.server.config.LocalRepoProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** The mirror in the user's Maven settings that sends every Maven build on this machine through the proxy. */
@Component
public class MavenSetup {

    private static final DateTimeFormatter BACKUP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final MavenSettingsEditor editor = new MavenSettingsEditor();
    private final Path settings;
    private final Clock clock;

    public MavenSetup(LocalRepoProperties properties, Clock clock) {
        this.settings = properties.mavenSettings();
        this.clock = clock;
    }

    public record Status(boolean installed, String path) {
    }

    /** A complete settings file with only the mirror, for {@code mvn -s} or to copy from. */
    public String render(String baseUrl) {
        return editor.install(null, mirrorUrl(baseUrl)).replace("<!-- LocalRepoServer:created -->\n", "");
    }

    public Status status(String baseUrl) throws IOException {
        boolean installed = Files.isRegularFile(settings)
                && editor.isInstalled(Files.readString(settings), mirrorUrl(baseUrl));
        return new Status(installed, settings.toString());
    }

    public Status install(String baseUrl) throws IOException {
        String existing = Files.isRegularFile(settings) ? Files.readString(settings) : null;
        String updated = editor.install(existing, mirrorUrl(baseUrl));
        backUp();
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, updated);
        return status(baseUrl);
    }

    public Status uninstall(String baseUrl) throws IOException {
        if (Files.isRegularFile(settings)) {
            String restored = editor.uninstall(Files.readString(settings));
            backUp();
            if (restored == null) {
                Files.delete(settings);
            } else {
                Files.writeString(settings, restored);
            }
        }
        return status(baseUrl);
    }

    private void backUp() throws IOException {
        if (Files.isRegularFile(settings)) {
            Path backup = settings.resolveSibling(settings.getFileName() + ".localrepo-bak-"
                    + BACKUP_SUFFIX.format(clock.instant()));
            Files.copy(settings, backup);
        }
    }

    private static String mirrorUrl(String baseUrl) {
        return baseUrl + "/cache";
    }
}
