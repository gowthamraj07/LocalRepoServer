package com.localrepo.server.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CacheLocationTest {

    @TempDir
    Path tmp;

    @Test
    void preparingCreatesTheDirectory() {
        CacheLocation location = new CacheLocation(tmp.resolve("disk/cache"));

        assertTrue(location.prepare());

        assertTrue(Files.isDirectory(tmp.resolve("disk/cache")));
        assertTrue(location.available());
        assertTrue(location.freeSpace() > 0);
    }

    @Test
    void isUnavailableWhileTheDirectoryIsGone() throws Exception {
        CacheLocation location = new CacheLocation(tmp.resolve("cache"));
        location.prepare();
        Files.delete(tmp.resolve("cache"));

        assertFalse(location.available());
        assertEquals(0, location.freeSpace());
        assertFalse(Files.exists(tmp.resolve("cache")), "checking must not recreate it");
    }

    @Test
    void preparingReportsADirectoryThatCannotBeCreated() throws Exception {
        Path file = Files.createFile(tmp.resolve("not-a-directory"));
        CacheLocation location = new CacheLocation(file.resolve("cache"));

        assertFalse(location.prepare());
        assertFalse(location.available());
    }
}
