package com.localrepo.server.setup;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The Gradle init script that sends every build on this machine through the proxy. */
@Component
public class GradleSetup {

    public static final String FILE_NAME = "localrepo.init.gradle";

    private final String template;

    public GradleSetup() {
        try {
            template = new ClassPathResource("setup/" + FILE_NAME).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String render(String baseUrl) {
        return template.replace("@BASE_URL@", baseUrl);
    }
}
