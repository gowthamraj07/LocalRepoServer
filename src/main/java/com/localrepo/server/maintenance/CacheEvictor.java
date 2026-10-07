package com.localrepo.server.maintenance;

import com.localrepo.server.api.AccessStats;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Keeps the cache under a size limit by deleting the least recently used files first. Once over the limit it goes
 * down to 90% of it, so it does not run again for every new download. Pinned paths are never deleted.
 */
public class CacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictor.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final double LOW_WATERMARK = 0.9;

    private final ArtifactService service;
    private final AccessStats stats;
    private final long maxBytes;
    private final List<String> pinned;

    /** @param maxBytes zero or less means no limit */
    public CacheEvictor(ArtifactService service, AccessStats stats, long maxBytes, List<String> pinned) {
        this.service = service;
        this.stats = stats;
        this.maxBytes = maxBytes;
        this.pinned = List.copyOf(pinned);
    }

    /** @param deleted {@code <repository>/<path>} of each file removed */
    public record Result(long maxBytes, long sizeBefore, long sizeAfter, List<String> deleted) {
    }

    private record Candidate(Repository repository, CachedArtifact artifact, String key, Instant lastUsed) {
    }

    public synchronized Result evict() throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        long size = 0;
        for (Map.Entry<Repository, List<CachedArtifact>> entry : service.list().entrySet()) {
            for (CachedArtifact artifact : entry.getValue()) {
                long fileSize = Math.max(0, artifact.meta().size());
                size += fileSize;
                if (isPinned(artifact)) {
                    continue;
                }
                String key = entry.getKey().name() + "/" + artifact.path().value();
                Instant lastAccess = stats.access(key).lastAccess();
                candidates.add(new Candidate(entry.getKey(), artifact, key,
                        lastAccess != null ? lastAccess : artifact.meta().fetchedAt()));
            }
        }
        long before = size;
        List<String> deleted = new ArrayList<>();
        if (maxBytes > 0 && size > maxBytes) {
            long target = (long) (maxBytes * LOW_WATERMARK);
            candidates.sort(Comparator.comparing(Candidate::lastUsed, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (Candidate candidate : candidates) {
                if (size <= target) {
                    break;
                }
                candidate.repository().store().delete(candidate.artifact().path());
                stats.forget(candidate.key());
                size -= Math.max(0, candidate.artifact().meta().size());
                deleted.add(candidate.key());
            }
            log.info("Cache was {} bytes, over its {} byte limit; removed {} least recently used files", before,
                    maxBytes, deleted.size());
        }
        return new Result(maxBytes, before, size, deleted);
    }

    private boolean isPinned(CachedArtifact artifact) {
        return pinned.stream().anyMatch(pattern -> MATCHER.match(pattern, artifact.path().value()));
    }
}
