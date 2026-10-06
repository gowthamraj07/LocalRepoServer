package com.localrepo.server.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DownloadTest {

    private static final ArtifactPath PATH = ArtifactPath.of("g/a/1/a-1.jar");
    private static final Origin ORIGIN = new Origin("https://up/g/a/1/a-1.jar", null, null);

    @TempDir
    Path root;
    private ArtifactStore store;
    private Download download;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(root, Clock.systemUTC());
        download = new Download(PATH, Clock.systemUTC());
    }

    @Test
    void readerFollowsTheBytesAsTheyArrive() throws Exception {
        ArtifactStore.PendingWrite write = store.begin(PATH);
        download.streaming(ORIGIN, 6, write.partFile(), () -> { });
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (InputStream in = download.openStream()) {
                byte[] buffer = new byte[2];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (received) {
                        received.write(buffer, 0, n);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        write(write, "abc");
        while (size(received) < 3) {
            Thread.sleep(5);
        }
        assertFalse(reader.isDone());

        write(write, "def");
        download.complete(() -> write.commit(ORIGIN));

        reader.get(5, TimeUnit.SECONDS);
        assertEquals("abcdef", received.toString(StandardCharsets.UTF_8));
    }

    @Test
    void readerFailsWhenTheDownloadFails() throws Exception {
        ArtifactStore.PendingWrite write = store.begin(PATH);
        download.streaming(ORIGIN, 6, write.partFile(), () -> { });
        write(write, "abc");
        InputStream in = download.openStream();
        assertEquals(3, in.read(new byte[10]));

        download.failed(new IOException("connection reset"));
        write.close();

        assertThrows(IOException.class, () -> in.read(new byte[10]));
    }

    @Test
    void readerOpenedAfterCompletionReadsTheCommittedFile() throws Exception {
        ArtifactStore.PendingWrite write = store.begin(PATH);
        download.streaming(ORIGIN, 3, write.partFile(), () -> { });
        write(write, "xyz");
        download.complete(() -> write.commit(ORIGIN));

        try (InputStream in = download.openStream()) {
            assertEquals("xyz", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(PATH, download.awaitResult().orElseThrow().path());
    }

    @Test
    void headersResolveToNotFound() throws Exception {
        CompletableFuture<Download.State> headers = CompletableFuture.supplyAsync(() -> {
            try {
                return download.awaitHeaders();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(50);
        assertFalse(headers.isDone());

        download.notFound();

        assertEquals(Download.State.NOT_FOUND, headers.get(5, TimeUnit.SECONDS));
        assertTrue(download.awaitResult().isEmpty());
    }

    @Test
    void abortingClosesTheUpstreamBody() throws Exception {
        ArtifactStore.PendingWrite write = store.begin(PATH);
        boolean[] closed = {false};
        download.streaming(ORIGIN, -1, write.partFile(), () -> closed[0] = true);

        download.abortUpstream();

        assertTrue(closed[0]);
    }

    private void write(ArtifactStore.PendingWrite write, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        write.write(bytes, 0, bytes.length);
        download.progress(write.size());
    }

    private static int size(ByteArrayOutputStream out) {
        synchronized (out) {
            return out.size();
        }
    }
}
