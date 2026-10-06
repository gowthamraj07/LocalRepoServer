package com.localrepo.server.setup;

import com.localrepo.server.config.LocalRepoProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The Gradle init script that sends every build on this machine through the proxy, and its installation. */
@Component
public class GradleSetup {

    public static final String FILE_NAME = "localrepo.init.gradle";

    private final String template;
    private final Path installedScript;

    public GradleSetup(LocalRepoProperties properties) {
        try {
            template = new ClassPathResource("setup/" + FILE_NAME).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        installedScript = properties.gradleUserHome().resolve("init.d").resolve(FILE_NAME);
    }

    public record Status(boolean installed, boolean current, String path) {
    }

    public String render(String baseUrl) {
        return template.replace("@BASE_URL@", baseUrl);
    }

    public Status status(String baseUrl) throws IOException {
        boolean installed = Files.isRegularFile(installedScript);
        boolean current = installed && Files.readString(installedScript).equals(render(baseUrl));
        return new Status(installed, current, installedScript.toString());
    }

    public Status install(String baseUrl) throws IOException {
        Files.createDirectories(installedScript.getParent());
        Files.writeString(installedScript, render(baseUrl));
        return status(baseUrl);
    }

    public Status uninstall(String baseUrl) throws IOException {
        Files.deleteIfExists(installedScript);
        return status(baseUrl);
    }
}
