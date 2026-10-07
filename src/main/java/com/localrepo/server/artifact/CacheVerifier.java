package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Re-hashes every cached artifact against the SHA-256 recorded when it was downloaded, and deletes the ones that no
 * longer match so the next request fetches them again.
 */
public class CacheVerifier {

    private static final Logger log = LoggerFactory.getLogger(CacheVerifier.class);

    private final ArtifactService service;
    private final Clock clock;
    private volatile Report report = new Report(false, null, null, 0, 0, List.of());

    public CacheVerifier(ArtifactService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * @param unverifiable files without a recorded checksum, e.g. copied into the cache by hand
     * @param corrupt      {@code <repository>/<path>} of every file that was deleted
     */
    public record Report(boolean running, Instant startedAt, Instant finishedAt, int checked, int unverifiable,
                         List<String> corrupt) {
    }

    /** Starts a scan in the background unless one is running; returns the current report. */
    public synchronized Report start() {
        if (!report.running()) {
            report = new Report(true, clock.instant(), null, 0, 0, List.of());
            Thread.ofVirtual().name("cache-verifier").start(this::scan);
        }
        return report;
    }

    public Report report() {
        return report;
    }

    private void scan() {
        int checked = 0;
        int unverifiable = 0;
        List<String> corrupt = new ArrayList<>();
        try {
            for (Map.Entry<Repository, List<CachedArtifact>> entry : service.list().entrySet()) {
                for (CachedArtifact artifact : entry.getValue()) {
                    String expected = artifact.meta().sha256();
                    if (expected == null) {
                        unverifiable++;
                        continue;
                    }
                    checked++;
                    if (!expected.equals(sha256(artifact))) {
                        String name = entry.getKey().name() + "/" + artifact.path().value();
                        log.warn("{} is corrupt; deleting it so it is downloaded again", name);
                        entry.getKey().store().delete(artifact.path());
                        corrupt.add(name);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("Cache verification stopped", e);
        } finally {
            report = new Report(false, report.startedAt(), clock.instant(), checked, unverifiable, List.copyOf(corrupt));
        }
    }

    private static String sha256(CachedArtifact artifact) throws IOException {
        try (InputStream in = Files.newInputStream(artifact.file())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
