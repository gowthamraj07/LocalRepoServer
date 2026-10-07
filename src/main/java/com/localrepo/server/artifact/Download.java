package com.localrepo.server.artifact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One upstream fetch of one artifact, shared by every request for it. A background task writes the bytes into the
 * store's part file; any number of readers follow that file as it grows via {@link #openStream()}.
 */
public final class Download {

    public enum State { CONNECTING, STREAMING, COMPLETED, NOT_FOUND, FAILED }

    @FunctionalInterface
    public interface Commit {
        CachedArtifact run() throws IOException;
    }

    private final ArtifactPath path;
    private final Clock clock;
    private final Instant startedAt;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private volatile State state = State.CONNECTING;
    private volatile long bytesWritten;
    private volatile long transferred;
    private volatile Instant lastActivity;
    private volatile Instant finishedAt;
    private volatile boolean stale;
    private Origin origin;
    private long contentLength = -1;
    private Path partFile;
    private Closeable upstreamBody;
    private CachedArtifact result;
    private IOException failure;

    public Download(ArtifactPath path, Clock clock) {
        this.path = path;
        this.clock = clock;
        this.startedAt = clock.instant();
        this.lastActivity = startedAt;
    }

    // --- producer side ---

    void streaming(Origin origin, long contentLength, Path partFile, Closeable upstreamBody) {
        update(() -> {
            this.origin = origin;
            this.contentLength = contentLength;
            this.partFile = partFile;
            this.upstreamBody = upstreamBody;
            this.state = State.STREAMING;
        });
    }

    void progress(long bytesWritten) {
        update(() -> {
            this.bytesWritten = bytesWritten;
            this.transferred = bytesWritten;
        });
    }

    /** Runs {@code commit} under the lock so no reader can open the part file after it has been moved. */
    void complete(Commit commit) throws IOException {
        lock.lock();
        try {
            result = commit.run();
            bytesWritten = result.meta().size();
            finish(State.COMPLETED);
            lastActivity = clock.instant();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Finishes with a copy we already had because the upstream could not confirm or replace it. */
    void completeStale(CachedArtifact artifact) throws IOException {
        stale = true;
        complete(() -> artifact);
    }

    void notFound() {
        update(() -> finish(State.NOT_FOUND));
    }

    void failed(IOException cause) {
        update(() -> {
            failure = cause;
            finish(State.FAILED);
        });
    }

    /** Breaks a stalled upstream read; the producer then sees an IOException and fails the download. */
    void abortUpstream() {
        Closeable body;
        lock.lock();
        try {
            body = upstreamBody;
        } finally {
            lock.unlock();
        }
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // already broken
            }
        }
    }

    // --- consumer side ---

    /** Blocks until the upstream has answered: STREAMING, or a terminal state. */
    public State awaitHeaders() throws IOException {
        return awaitWhile(() -> state == State.CONNECTING);
    }

    /** Blocks until the download has finished. Empty unless it completed. */
    public Optional<CachedArtifact> awaitResult() throws IOException {
        awaitWhile(() -> !isFinished());
        return Optional.ofNullable(result);
    }

    /** A stream over the artifact's bytes that blocks for bytes not yet downloaded. */
    public InputStream openStream() throws IOException {
        lock.lock();
        try {
            Path file = switch (state) {
                case STREAMING -> partFile;
                case COMPLETED -> result.file();
                default -> throw new IOException("Download of " + path.value() + " is " + state);
            };
            return new FollowingInputStream(FileChannel.open(file, StandardOpenOption.READ));
        } finally {
            lock.unlock();
        }
    }

    public ArtifactPath path() {
        return path;
    }

    public State state() {
        return state;
    }

    public boolean isFinished() {
        State s = state;
        return s == State.COMPLETED || s == State.NOT_FOUND || s == State.FAILED;
    }

    /** Whether the result is an old copy served because the upstream could not be reached. */
    public boolean isStale() {
        return stale;
    }

    /** Bytes actually received from an upstream, as opposed to served from an existing copy. */
    public long transferred() {
        return transferred;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public Origin origin() {
        lock.lock();
        try {
            return origin;
        } finally {
            lock.unlock();
        }
    }

    /** Upstream Content-Length, or -1 when unknown. */
    public long contentLength() {
        lock.lock();
        try {
            return contentLength;
        } finally {
            lock.unlock();
        }
    }

    private void finish(State terminal) {
        state = terminal;
        finishedAt = clock.instant();
    }

    private void update(Runnable change) {
        lock.lock();
        try {
            change.run();
            lastActivity = clock.instant();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private State awaitWhile(java.util.function.BooleanSupplier condition) throws IOException {
        lock.lock();
        try {
            while (condition.getAsBoolean()) {
                changed.await();
            }
            return state;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for " + path.value());
        } finally {
            lock.unlock();
        }
    }

    /**
     * How far a reader may read. Until the download is committed, the last byte of a known-length file is held back:
     * the client then cannot finish its transfer before the checks behind the commit pass, so a download rejected at
     * the end still looks broken to it rather than complete.
     */
    private long readableLimit() {
        if (state == State.COMPLETED || contentLength < 0) {
            return bytesWritten;
        }
        return Math.min(bytesWritten, contentLength - 1);
    }

    private final class FollowingInputStream extends InputStream {

        private final FileChannel channel;
        private long position;

        private FollowingInputStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long available;
            lock.lock();
            try {
                while ((available = readableLimit() - position) <= 0) {
                    if (state == State.COMPLETED) {
                        return -1;
                    }
                    if (state == State.FAILED || state == State.NOT_FOUND) {
                        throw new IOException("Download of " + path.value() + " failed", failure);
                    }
                    changed.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted reading " + path.value());
            } finally {
                lock.unlock();
            }
            int n = channel.read(ByteBuffer.wrap(buffer, offset, (int) Math.min(length, available)), position);
            if (n > 0) {
                position += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
