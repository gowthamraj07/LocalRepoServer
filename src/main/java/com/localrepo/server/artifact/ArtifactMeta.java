package com.localrepo.server.artifact;

import java.time.Instant;

/**
 * Sidecar data stored next to each cached artifact as {@code <file>.meta.json}.
 *
 * @param checksumVerified the upstream checksum the bytes were checked against ({@code sha256} or {@code sha1}), or
 *                         null when the upstream published none
 */
public record ArtifactMeta(String upstreamUrl, String etag, String lastModified, Instant fetchedAt, long size,
                           String sha256, String sha1, String checksumVerified) {

    public ArtifactMeta(String upstreamUrl, String etag, String lastModified, Instant fetchedAt, long size,
                        String sha256) {
        this(upstreamUrl, etag, lastModified, fetchedAt, size, sha256, null, null);
    }
}
