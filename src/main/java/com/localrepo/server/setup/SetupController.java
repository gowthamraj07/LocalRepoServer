package com.localrepo.server.setup;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class SetupController {

    private final GradleSetup gradle;

    public SetupController(GradleSetup gradle) {
        this.gradle = gradle;
    }

    @GetMapping(value = "/setup/gradle/" + GradleSetup.FILE_NAME, produces = MediaType.TEXT_PLAIN_VALUE)
    public String gradleInitScript() {
        return gradle.render(baseUrl());
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
    }
}
