package com.localrepo.server.artifact;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A repository-relative artifact path such as {@code junit/junit/4.13.2/junit-4.13.2.pom}, validated so it can
 * never escape the cache directory or address the store's own bookkeeping files.
 */
public record ArtifactPath(String value) {

    static final String META_SUFFIX = ".meta.json";
    static final String PART_SUFFIX = ".part";

    public static ArtifactPath of(String raw) {
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidArtifactPathException("Malformed encoding: " + raw);
        }

        if (decoded.isEmpty() || decoded.startsWith("/") || decoded.contains("\\") || decoded.indexOf('\0') >= 0) {
            throw new InvalidArtifactPathException("Not a relative repository path: " + raw);
        }

        String normalized = Arrays.stream(decoded.split("/"))
                .filter(segment -> !segment.isEmpty())
                .peek(segment -> {
                    if (segment.equals(".") || segment.equals("..")) {
                        throw new InvalidArtifactPathException("Path traversal is not allowed: " + raw);
                    }
                })
                .collect(Collectors.joining("/"));

        if (normalized.isEmpty() || normalized.endsWith(META_SUFFIX) || normalized.endsWith(PART_SUFFIX)) {
            throw new InvalidArtifactPathException("Not an artifact path: " + raw);
        }
        return new ArtifactPath(normalized);
    }

    public String fileName() {
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
