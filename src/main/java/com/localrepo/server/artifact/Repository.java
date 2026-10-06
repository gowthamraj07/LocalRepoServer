package com.localrepo.server.artifact;

import org.springframework.util.AntPathMatcher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** One upstream repository, the part of the cache that holds what it served, and which paths it is asked for. */
public final class Repository {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final String name;
    private final String url;
    private final List<String> includes;
    private final List<String> excludes;
    private final Credentials credentials;
    private final ArtifactStore store;

    public Repository(String name, String url, List<String> includes, List<String> excludes, Credentials credentials,
                      ArtifactStore store) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Repository name must match " + NAME + ": " + name);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Repository " + name + " has no url");
        }
        this.name = name;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.credentials = credentials;
        this.store = store;
    }

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    public ArtifactStore store() {
        return store;
    }

    /** Whether the group should ask this repository for {@code path}. */
    public boolean accepts(ArtifactPath path) {
        String value = path.value();
        boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> MATCHER.match(p, value));
        return included && excludes.stream().noneMatch(p -> MATCHER.match(p, value));
    }

    /** The {@code Authorization} header value for requests to this repository, if it needs one. */
    public Optional<String> authorization() {
        return credentials.header();
    }

    @Override
    public String toString() {
        return "Repository[" + name + " " + url + "]";
    }

    public record Credentials(String username, String password, String token) {

        public static final Credentials NONE = new Credentials(null, null, null);

        public static Credentials basic(String username, String password) {
            return new Credentials(username, password, null);
        }

        public static Credentials bearer(String token) {
            return new Credentials(null, null, token);
        }

        Optional<String> header() {
            if (token != null && !token.isBlank()) {
                return Optional.of("Bearer " + token);
            }
            if (username != null && password != null) {
                String pair = username + ":" + password;
                return Optional.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
            }
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "Credentials[***]";
        }
    }
}
