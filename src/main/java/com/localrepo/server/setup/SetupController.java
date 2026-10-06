package com.localrepo.server.setup;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;

/**
 * Sets up build tools on this machine to use the proxy. Changes require the {@value #ACTION_HEADER} header: a browser
 * only sends a custom header cross-origin after a CORS preflight, which this server never allows, so another website
 * cannot change the setup.
 */
@RestController
public class SetupController {

    public static final String ACTION_HEADER = "X-LocalRepo-Action";

    private final GradleSetup gradle;

    public SetupController(GradleSetup gradle) {
        this.gradle = gradle;
    }

    @GetMapping(value = "/setup/gradle/" + GradleSetup.FILE_NAME, produces = MediaType.TEXT_PLAIN_VALUE)
    public String gradleInitScript() {
        return gradle.render(baseUrl());
    }

    @GetMapping("/setup/gradle")
    public GradleSetup.Status gradleStatus() throws IOException {
        return gradle.status(baseUrl());
    }

    @PostMapping("/setup/gradle/install")
    public GradleSetup.Status installGradle(@RequestHeader(value = ACTION_HEADER, required = false) String action)
            throws IOException {
        requireAction(action);
        return gradle.install(baseUrl());
    }

    @PostMapping("/setup/gradle/uninstall")
    public GradleSetup.Status uninstallGradle(@RequestHeader(value = ACTION_HEADER, required = false) String action)
            throws IOException {
        requireAction(action);
        return gradle.uninstall(baseUrl());
    }

    private static void requireAction(String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + ACTION_HEADER + " header");
        }
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
    }
}
