package com.localrepo.server.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Artifacts on disk in the standard Maven layout. A file is only ever visible under its final name once it has been
 * completely written, so a broken download can never be served.
 */
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path root;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ArtifactStore(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    public Optional<CachedArtifact> find(ArtifactPath path) {
        Path file = fileFor(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        ArtifactMeta meta = readMeta(file);
        try {
            if (meta.size() >= 0 && Files.size(file) != meta.size()) {
                log.warn("Dropping {}: {} bytes on disk but {} when downloaded", file, Files.size(file), meta.size());
                delete(path);
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(new CachedArtifact(path, file, meta));
    }

    /** Removes an artifact and its metadata; the next request fetches it again. */
    public void delete(ArtifactPath path) throws IOException {
        Path file = fileFor(path);
        Files.deleteIfExists(file);
        Files.deleteIfExists(metaFileFor(file));
    }

    public CachedArtifact save(ArtifactPath path, InputStream content, Origin origin) throws IOException {
        try (PendingWrite write = begin(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                write.write(buffer, 0, read);
            }
            return write.commit(origin);
        }
    }

    /**
     * Records that the upstream confirmed the cached copy is still current: the fetch time becomes now, and any new
     * validators replace the old ones.
     */
    public CachedArtifact refresh(ArtifactPath path, Origin confirmation) throws IOException {
        CachedArtifact cached = find(path).orElseThrow(() -> new IOException(path.value() + " is not cached"));
        ArtifactMeta old = cached.meta();
        ArtifactMeta meta = new ArtifactMeta(old.upstreamUrl(),
                confirmation.etag() != null ? confirmation.etag() : old.etag(),
                confirmation.lastModified() != null ? confirmation.lastModified() : old.lastModified(),
                clock.instant(), old.size(), old.sha256(), old.sha1(), old.checksumVerified());
        Path metaFile = metaFileFor(cached.file());
        Path part = Files.createTempFile(metaFile.getParent(), metaFile.getFileName().toString() + ".", ArtifactPath.PART_SUFFIX);
        try {
            json.writeValue(part.toFile(), meta);
            Files.move(part, metaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return new CachedArtifact(path, cached.file(), meta);
    }

    /** Starts writing {@code path}. Nothing is visible through {@link #find} until {@link PendingWrite#commit}. */
    public PendingWrite begin(ArtifactPath path) throws IOException {
        Path file = fileFor(path);
        Files.createDirectories(file.getParent());
        Path part = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ArtifactPath.PART_SUFFIX);
        return new PendingWrite(path, file, part);
    }

    /**
     * A download in progress. Bytes passed to {@link #write} reach the part file immediately, so other readers can
     * follow it. Closing without committing deletes the part file.
     */
    public final class PendingWrite implements AutoCloseable {

        private final ArtifactPath path;
        private final Path file;
        private final Path part;
        private final FileChannel channel;
        private final MessageDigest sha256 = digest("SHA-256");
        private final MessageDigest sha1 = digest("SHA-1");
        private long size;
        private Checksums checksums;
        private boolean committed;

        private PendingWrite(ArtifactPath path, Path file, Path part) throws IOException {
            this.path = path;
            this.file = file;
            this.part = part;
            this.channel = FileChannel.open(part, StandardOpenOption.WRITE);
        }

        public Path partFile() {
            return part;
        }

        public long size() {
            return size;
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (checksums != null) {
                throw new IllegalStateException("Checksums were already taken");
            }
            sha256.update(bytes, offset, length);
            sha1.update(bytes, offset, length);
            size += length;
        }

        /** Checksums of everything written; no more bytes can be written afterwards. */
        public Checksums checksums() {
            if (checksums == null) {
                HexFormat hex = HexFormat.of();
                checksums = new Checksums(hex.formatHex(sha256.digest()), hex.formatHex(sha1.digest()));
            }
            return checksums;
        }

        /** Moves the bytes out of the cache, next to it, for inspection. The write is over afterwards. */
        public Path quarantine() throws IOException {
            channel.close();
            Path target = root.resolveSibling(".quarantine").resolve(root.getFileName())
                    .resolve(path.value() + "." + clock.instant().toEpochMilli());
            Files.createDirectories(target.getParent());
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
            return target;
        }

        /** Makes the artifact visible under its final name. */
        public CachedArtifact commit(Origin origin) throws IOException {
            return commit(origin, null);
        }

        /**
         * Makes the artifact visible under its final name.
         *
         * @param checksumVerified the upstream checksum the bytes matched, or null if none was available
         */
        public CachedArtifact commit(Origin origin, String checksumVerified) throws IOException {
            channel.force(true);
            channel.close();
            Checksums sums = checksums();
            ArtifactMeta meta = new ArtifactMeta(origin.url(), origin.etag(), origin.lastModified(), clock.instant(),
                    size, sums.sha256(), sums.sha1(), checksumVerified);
            json.writeValue(metaFileFor(file).toFile(), meta);
            makeReadable(part);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            return new CachedArtifact(path, file, meta);
        }

        @Override
        public void close() throws IOException {
            if (committed) {
                return;
            }
            channel.close();
            Files.deleteIfExists(part);
            if (!Files.exists(file)) {
                Files.deleteIfExists(metaFileFor(file));
            }
        }
    }

    public List<CachedArtifact> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(ArtifactPath.META_SUFFIX))
                    .filter(f -> !f.getFileName().toString().endsWith(ArtifactPath.PART_SUFFIX))
                    .map(f -> new CachedArtifact(new ArtifactPath(relative(f)), f, readMeta(f)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ArtifactMeta readMeta(Path file) {
        Path metaFile = metaFileFor(file);
        if (Files.isRegularFile(metaFile)) {
            try {
                return json.readValue(metaFile.toFile(), ArtifactMeta.class);
            } catch (IOException e) {
                log.warn("Ignoring unreadable metadata {}", metaFile, e);
            }
        }
        try {
            return new ArtifactMeta(null, null, null, Files.getLastModifiedTime(file).toInstant(), Files.size(file),
                    null);
        } catch (IOException e) {
            return new ArtifactMeta(null, null, null, Instant.EPOCH, -1, null);
        }
    }

    private Path fileFor(ArtifactPath path) {
        return root.resolve(path.value());
    }

    private static Path metaFileFor(Path file) {
        return file.resolveSibling(file.getFileName() + ArtifactPath.META_SUFFIX);
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    /** Temp files are created owner-only; the cache should be readable like any Maven repository. */
    private static void makeReadable(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    public record Checksums(String sha256, String sha1) {
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
