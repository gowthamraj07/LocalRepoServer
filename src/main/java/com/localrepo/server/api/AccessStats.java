package com.localrepo.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How the cache is used: lifetime totals, and hits and last access per cached file (keyed by its path below the cache
 * directory, {@code <repository>/<path>}). Kept in memory and saved to {@code <cacheDir>/.index.json}.
 */
public class AccessStats implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AccessStats.class);

    private final Path cacheDir;
    private final Path indexFile;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong bytesServedFromCache = new AtomicLong();
    private final AtomicLong bytesDownloaded = new AtomicLong();
    private final Map<String, FileAccess> files = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private ScheduledExecutorService flusher;

    public record Totals(long hits, long misses, long bytesServedFromCache, long bytesDownloaded) {
    }

    public record FileAccess(long hits, Instant lastAccess) {
        static final FileAccess NEVER = new FileAccess(0, null);
    }

    private record Index(Totals totals, Map<String, FileAccess> files) {
    }

    public AccessStats(Path cacheDir, Clock clock) {
        this.cacheDir = cacheDir;
        this.indexFile = cacheDir.resolve(".index.json");
        this.clock = clock;
        load();
    }

    /** A request was answered from the cache. */
    public void hit(Path file, long bytes) {
        hits.incrementAndGet();
        bytesServedFromCache.addAndGet(Math.max(0, bytes));
        files.merge(key(file), new FileAccess(1, clock.instant()),
                (old, now) -> new FileAccess(old.hits() + 1, now.lastAccess()));
        dirty.set(true);
    }

    /** A file was just downloaded: it counts as used now, without counting as a hit. */
    public void touch(Path file) {
        files.merge(key(file), new FileAccess(0, clock.instant()),
                (old, now) -> new FileAccess(old.hits(), now.lastAccess()));
        dirty.set(true);
    }

    /** A request had to go to an upstream. */
    public void miss() {
        misses.incrementAndGet();
        dirty.set(true);
    }

    public void downloaded(long bytes) {
        bytesDownloaded.addAndGet(Math.max(0, bytes));
        dirty.set(true);
    }

    public void forget(String key) {
        if (files.remove(key) != null) {
            dirty.set(true);
        }
    }

    public Totals totals() {
        return new Totals(hits.get(), misses.get(), bytesServedFromCache.get(), bytesDownloaded.get());
    }

    public FileAccess access(String key) {
        return files.getOrDefault(key, FileAccess.NEVER);
    }

    public String key(Path file) {
        return cacheDir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    public void startPeriodicFlush(Duration every) {
        flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("stats-flush").factory());
        flusher.scheduleWithFixedDelay(this::flushQuietly, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void flush() throws IOException {
        if (!dirty.getAndSet(false)) {
            return;
        }
        Files.createDirectories(cacheDir);
        Path part = Files.createTempFile(cacheDir, ".index.json.", ".part");
        try {
            json.writeValue(part.toFile(), new Index(totals(), Map.copyOf(files)));
            Files.move(part, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            dirty.set(true);
            throw e;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    @Override
    public void close() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not save {}", indexFile, e);
        }
    }

    private void load() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            Index index = json.readValue(indexFile.toFile(), Index.class);
            if (index.totals() != null) {
                hits.set(index.totals().hits());
                misses.set(index.totals().misses());
                bytesServedFromCache.set(index.totals().bytesServedFromCache());
                bytesDownloaded.set(index.totals().bytesDownloaded());
            }
            if (index.files() != null) {
                files.putAll(index.files());
            }
        } catch (IOException e) {
            log.warn("Ignoring unreadable {}", indexFile, e);
        }
    }
}
