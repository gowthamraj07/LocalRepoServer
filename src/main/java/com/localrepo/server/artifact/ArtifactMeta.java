package com.localrepo.server.artifact;

import java.time.Instant;

/** Sidecar data stored next to each cached artifact as {@code <file>.meta.json}. */
public record ArtifactMeta(String upstreamUrl, String etag, String lastModified, Instant fetchedAt, long size,
                           String sha256) {
}
