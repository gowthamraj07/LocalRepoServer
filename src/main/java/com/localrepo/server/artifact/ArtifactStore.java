package com.localrepo.server.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
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
        return Optional.of(new CachedArtifact(path, file, readMeta(file)));
    }

    public CachedArtifact save(ArtifactPath path, InputStream content, Origin origin) throws IOException {
        Path file = fileFor(path);
        Files.createDirectories(file.getParent());
        Path part = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ArtifactPath.PART_SUFFIX);
        try {
            MessageDigest sha256 = sha256();
            long size;
            try (InputStream in = new DigestInputStream(content, sha256);
                 FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE);
                 OutputStream out = Channels.newOutputStream(channel)) {
                size = in.transferTo(out);
                out.flush();
                channel.force(true);
            }
            ArtifactMeta meta = new ArtifactMeta(origin.url(), origin.etag(), origin.lastModified(), clock.instant(),
                    size, HexFormat.of().formatHex(sha256.digest()));
            json.writeValue(metaFileFor(file).toFile(), meta);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new CachedArtifact(path, file, meta);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            if (!Files.exists(file)) {
                Files.deleteIfExists(metaFileFor(file));
            }
            throw e;
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
