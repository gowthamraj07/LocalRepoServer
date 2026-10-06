package com.localrepo.server.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactStoreTest {

    private static final ArtifactPath POM = ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.pom");
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final Origin ORIGIN = new Origin("https://repo.example/maven2/junit/junit/4.13.2/junit-4.13.2.pom",
            "\"abc\"", "Tue, 06 Oct 2026 10:00:00 GMT");

    @TempDir
    Path root;
    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(root, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void findsNothingInAnEmptyStore() {
        assertTrue(store.find(POM).isEmpty());
    }

    @Test
    void savesUnderTheMavenLayoutAndFindsItAgain() throws IOException {
        CachedArtifact saved = store.save(POM, bytes("<project/>"), ORIGIN);

        assertEquals(root.resolve("junit/junit/4.13.2/junit-4.13.2.pom"), saved.file());
        assertEquals("<project/>", Files.readString(saved.file()));
        CachedArtifact found = store.find(POM).orElseThrow();
        assertEquals(saved.file(), found.file());
        assertEquals(saved.meta(), found.meta());
    }

    @Test
    void recordsWhereAndWhenTheArtifactCameFrom() throws IOException {
        ArtifactMeta meta = store.save(POM, bytes("<project/>"), ORIGIN).meta();

        assertEquals(ORIGIN.url(), meta.upstreamUrl());
        assertEquals(ORIGIN.etag(), meta.etag());
        assertEquals(ORIGIN.lastModified(), meta.lastModified());
        assertEquals(NOW, meta.fetchedAt());
        assertEquals(10, meta.size());
        assertEquals("618a333ca21cdc97fc758355c6127cb83ae2a1b0c892a8b7a1b726b02d378a54", meta.sha256());
    }

    @Test
    void makesCommittedArtifactsWorldReadable() throws IOException {
        Path file = store.save(POM, bytes("<project/>"), ORIGIN).file();

        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(file));
    }

    @Test
    void writesTheMetadataSidecarNextToTheArtifact() throws IOException {
        store.save(POM, bytes("<project/>"), ORIGIN);

        assertTrue(Files.exists(root.resolve("junit/junit/4.13.2/junit-4.13.2.pom.meta.json")));
    }

    @Test
    void leavesNoFileBehindWhenTheDownloadBreaksMidway() throws IOException {
        InputStream broken = new InputStream() {
            int served;

            @Override
            public int read() throws IOException {
                if (served++ > 100) {
                    throw new IOException("connection reset");
                }
                return 'x';
            }
        };

        assertThrows(IOException.class, () -> store.save(POM, broken, ORIGIN));

        assertTrue(store.find(POM).isEmpty());
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(List.of(), files.filter(Files::isRegularFile).toList());
        }
    }

    @Test
    void servesAFileThatHasNoSidecar() throws IOException {
        Path file = root.resolve(POM.value());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project/>");

        CachedArtifact found = store.find(POM).orElseThrow();

        assertEquals(10, found.meta().size());
    }

    @Test
    void listsOnlyArtifactsNotBookkeepingFiles() throws IOException {
        store.save(POM, bytes("<project/>"), ORIGIN);
        store.save(ArtifactPath.of("junit/junit/4.13.2/junit-4.13.2.jar"), bytes("jar"), ORIGIN);

        assertEquals(List.of("junit/junit/4.13.2/junit-4.13.2.jar", "junit/junit/4.13.2/junit-4.13.2.pom"),
                store.list().stream().map(a -> a.path().value()).sorted().toList());
    }

    @Test
    void exposesAnInProgressWriteOnlyThroughItsPartFile() throws IOException {
        try (ArtifactStore.PendingWrite write = store.begin(POM)) {
            write.write("<proj".getBytes(StandardCharsets.UTF_8), 0, 5);

            assertEquals("<proj", Files.readString(write.partFile()));
            assertEquals(5, write.size());
            assertTrue(store.find(POM).isEmpty());

            write.write("ect/>".getBytes(StandardCharsets.UTF_8), 0, 5);
            CachedArtifact committed = write.commit(ORIGIN);

            assertEquals("<project/>", Files.readString(committed.file()));
            assertEquals(store.find(POM).orElseThrow().meta(), committed.meta());
            assertFalse(Files.exists(write.partFile()));
        }
    }

    @Test
    void closingAnUncommittedWriteDiscardsIt() throws IOException {
        Path part;
        try (ArtifactStore.PendingWrite write = store.begin(POM)) {
            write.write(new byte[]{1, 2, 3}, 0, 3);
            part = write.partFile();
        }

        assertFalse(Files.exists(part));
        assertTrue(store.find(POM).isEmpty());
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
