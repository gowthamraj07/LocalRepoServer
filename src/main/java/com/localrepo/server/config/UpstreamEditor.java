package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.Repository;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds and removes extra upstreams while the server runs, and keeps them in {@code upstreams.yml} in LocalRepoServer's
 * home so they are back after a restart. The upstreams from the main configuration cannot be changed here.
 */
public class UpstreamEditor {

    private static final String HEADER = """
            # Written by LocalRepoServer's Upstreams page; changes here apply on the next start.
            # Credentials are named, never stored: environment variables (*-env) or gradle.properties keys (gradle-property-*).
            """;

    private final Path file;
    private final ArtifactService service;
    private final RepositoryFactory repositories;
    private final List<LocalRepoProperties.Upstream> extras;

    public UpstreamEditor(Path file, List<LocalRepoProperties.Upstream> extras, ArtifactService service,
                          RepositoryFactory repositories) {
        this.file = file;
        this.service = service;
        this.repositories = repositories;
        this.extras = new ArrayList<>(extras);
    }

    public synchronized List<LocalRepoProperties.Upstream> extras() {
        return List.copyOf(extras);
    }

    public synchronized void add(LocalRepoProperties.Upstream upstream) throws IOException {
        URI url;
        try {
            url = URI.create(upstream.url() == null ? "" : upstream.url().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a URL: " + upstream.url());
        }
        if (!"https".equals(url.getScheme()) && !"http".equals(url.getScheme()) || url.getHost() == null) {
            throw new IllegalArgumentException("The URL must be http(s)://host/...: " + upstream.url());
        }
        Repository repository = repositories.create(upstream);
        service.addRepository(repository);
        extras.add(upstream);
        try {
            save();
        } catch (IOException | RuntimeException e) {
            extras.remove(upstream);
            service.removeRepository(repository.name());
            throw e;
        }
    }

    public synchronized void remove(String name) throws IOException {
        LocalRepoProperties.Upstream upstream = extras.stream().filter(u -> u.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " is not an upstream added here"));
        service.removeRepository(name);
        extras.remove(upstream);
        save();
    }

    private void save() throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LocalRepoProperties.Upstream upstream : extras) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", upstream.name());
            entry.put("url", upstream.url());
            if (!upstream.includes().isEmpty()) {
                entry.put("includes", upstream.includes());
            }
            if (!upstream.excludes().isEmpty()) {
                entry.put("excludes", upstream.excludes());
            }
            Map<String, String> credentials = credentials(upstream.credentials());
            if (!credentials.isEmpty()) {
                entry.put("credentials", credentials);
            }
            entries.add(entry);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        String yaml = HEADER + new Yaml(options).dump(Map.of("localrepo", Map.of("extra-upstreams", entries)));
        Files.createDirectories(file.getParent());
        Path part = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".part");
        try {
            Files.writeString(part, yaml);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static Map<String, String> credentials(LocalRepoProperties.Credentials c) {
        Map<String, String> names = new LinkedHashMap<>();
        putIfSet(names, "username-env", c.usernameEnv());
        putIfSet(names, "password-env", c.passwordEnv());
        putIfSet(names, "token-env", c.tokenEnv());
        putIfSet(names, "gradle-property-username", c.gradlePropertyUsername());
        putIfSet(names, "gradle-property-password", c.gradlePropertyPassword());
        putIfSet(names, "gradle-property-token", c.gradlePropertyToken());
        return names;
    }

    private static void putIfSet(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
