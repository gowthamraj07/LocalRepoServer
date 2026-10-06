package com.localrepo.server.artifact;

import java.time.Duration;
import java.time.Instant;

/** A point-in-time view of a {@link Download} for monitoring. {@code totalBytes} is -1 when unknown. */
public record DownloadProgress(String path, String upstreamUrl, Download.State state, long bytes, long totalBytes,
                               Instant startedAt, Instant finishedAt, long bytesPerSecond) {

    static DownloadProgress of(Download download, Instant now) {
        Origin origin = download.origin();
        Instant end = download.finishedAt().orElse(now);
        long millis = Math.max(1, Duration.between(download.startedAt(), end).toMillis());
        long bytes = download.bytesWritten();
        return new DownloadProgress(download.path().value(), origin == null ? null : origin.url(), download.state(),
                bytes, download.contentLength(), download.startedAt(), download.finishedAt().orElse(null),
                bytes * 1000 / millis);
    }
}
