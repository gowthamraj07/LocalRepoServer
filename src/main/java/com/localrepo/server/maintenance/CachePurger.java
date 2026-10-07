package com.localrepo.server.maintenance;

import com.localrepo.server.api.AccessStats;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.Repository;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deletes cached files by where they are and how long they have gone unused, in every repository. */
public class CachePurger {

    private final ArtifactService service;
    private final AccessStats stats;
    private final Clock clock;

    public CachePurger(ArtifactService service, AccessStats stats, Clock clock) {
        this.service = service;
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * @param path      only files at or below this path; null for any
     * @param unusedFor only files not used for at least this long; null for any
     * @return {@code <repository>/<path>} of each deleted file
     */
    public List<String> purge(String path, Duration unusedFor) throws IOException {
        if (path == null && unusedFor == null) {
            throw new IllegalArgumentException("Give a path, an unused-for duration, or both");
        }
        String prefix = path == null ? null : ArtifactPath.of(path).value();
        Instant cutoff = unusedFor == null ? null : clock.instant().minus(unusedFor);
        List<String> purged = new ArrayList<>();
        for (Map.Entry<Repository, List<CachedArtifact>> entry : service.list().entrySet()) {
            for (CachedArtifact artifact : entry.getValue()) {
                String value = artifact.path().value();
                String key = entry.getKey().name() + "/" + value;
                if (prefix != null && !value.equals(prefix) && !value.startsWith(prefix + "/")) {
                    continue;
                }
                if (cutoff != null) {
                    Instant lastAccess = stats.access(key).lastAccess();
                    Instant lastUsed = lastAccess != null ? lastAccess : artifact.meta().fetchedAt();
                    if (lastUsed != null && lastUsed.isAfter(cutoff)) {
                        continue;
                    }
                }
                entry.getKey().store().delete(artifact.path());
                stats.forget(key);
                purged.add(key);
            }
        }
        purged.sort(null);
        return purged;
    }
}
