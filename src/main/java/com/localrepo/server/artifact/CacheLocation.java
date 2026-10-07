package com.localrepo.server.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The directory holding the whole cache. It may be on a disk that is not always connected, so it is created once, at
 * startup, and never again: a disk that goes away must not be replaced by a new, empty cache on another disk.
 */
public class CacheLocation {

    private static final Logger log = LoggerFactory.getLogger(CacheLocation.class);

    private final Path dir;

    public CacheLocation(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** Creates the directory if it does not exist yet; false if it cannot be used. */
    public boolean prepare() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("The cache directory {} cannot be created ({}); is its disk connected?", dir, e.toString());
        }
        boolean available = available();
        if (available) {
            log.info("Caching in {}", dir);
        }
        return available;
    }

    public boolean available() {
        return Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /** Bytes free on the disk holding the cache; 0 while it is unavailable. */
    public long freeSpace() {
        return available() ? dir.toFile().getUsableSpace() : 0;
    }
}
