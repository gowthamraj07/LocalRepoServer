package com.localrepo.server.maintenance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.localrepo.server.api.AccessStats;
import com.localrepo.server.artifact.ArtifactMeta;
import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.InvalidArtifactPathException;
import com.localrepo.server.artifact.Origin;
import com.localrepo.server.artifact.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moves cached files between machines as a zip of {@code <repository>/<path>} entries with their metadata and a
 * manifest of SHA-256 checksums. Importing never overwrites a file the cache already has and rejects anything whose
 * bytes do not match the manifest, or whose path would leave the cache.
 */
public class CacheBundles {

    public static final String MANIFEST = "localrepo-bundle.json";
    private static final Logger log = LoggerFactory.getLogger(CacheBundles.class);
    private static final String META_SUFFIX = ".meta.json";

    private final ArtifactService service;
    private final AccessStats stats;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CacheBundles(ArtifactService service, AccessStats stats, Clock clock) {
        this.service = service;
        this.stats = stats;
        this.clock = clock;
    }

    /** Which files to export; every part is optional. */
    public record Selection(String repository, String path, Instant usedSince) {
    }

    public record ManifestFile(String repository, String path, long size, String sha256) {
    }

    public record Manifest(int format, Instant createdAt, List<ManifestFile> files) {
    }

    public record ImportResult(int imported, int alreadyCached, int unknownRepository, List<String> rejected) {
    }

    private record Selected(Repository repository, CachedArtifact artifact) {
    }

    public void export(Selection selection, OutputStream target) throws IOException {
        List<Selected> selected = select(selection);
        List<ManifestFile> files = selected.stream().map(s -> new ManifestFile(s.repository().name(),
                s.artifact().path().value(), s.artifact().meta().size(), s.artifact().meta().sha256())).toList();
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            json.writeValue(new NonClosing(zip), new Manifest(1, clock.instant(), files));
            zip.closeEntry();
            for (Selected s : selected) {
                String name = s.repository().name() + "/" + s.artifact().path().value();
                Path meta = s.artifact().file().resolveSibling(s.artifact().file().getFileName() + META_SUFFIX);
                if (Files.isRegularFile(meta)) {
                    zip.putNextEntry(new ZipEntry(name + META_SUFFIX));
                    Files.copy(meta, zip);
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(s.artifact().file(), zip);
                zip.closeEntry();
            }
        }
    }

    public ImportResult importBundle(InputStream source) throws IOException {
        Map<String, String> expectedSha256 = new HashMap<>();
        Map<String, ArtifactMeta> metas = new HashMap<>();
        int imported = 0;
        int alreadyCached = 0;
        int unknownRepository = 0;
        List<String> rejected = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.equals(MANIFEST)) {
                    Manifest manifest = json.readValue(zip.readNBytes(64 * 1024 * 1024), Manifest.class);
                    manifest.files().forEach(f -> expectedSha256.put(f.repository() + "/" + f.path(), f.sha256()));
                    continue;
                }
                if (name.endsWith(META_SUFFIX)) {
                    metas.put(name.substring(0, name.length() - META_SUFFIX.length()),
                            json.readValue(zip.readNBytes(64 * 1024), ArtifactMeta.class));
                    continue;
                }
                int slash = name.indexOf('/');
                Repository repository = slash < 0 ? null : service.repositories().stream()
                        .filter(r -> r.name().equals(name.substring(0, slash))).findFirst().orElse(null);
                if (repository == null) {
                    unknownRepository++;
                    continue;
                }
                ArtifactPath path;
                try {
                    path = ArtifactPath.of(name.substring(slash + 1));
                } catch (InvalidArtifactPathException e) {
                    rejected.add(name);
                    continue;
                }
                if (repository.store().find(path).isPresent()) {
                    alreadyCached++;
                    continue;
                }
                ArtifactMeta meta = metas.get(name);
                String expected = expectedSha256.getOrDefault(name, meta == null ? null : meta.sha256());
                if (store(repository.store(), path, zip, meta, expected)) {
                    imported++;
                } else {
                    rejected.add(name);
                }
            }
        }
        log.info("Imported {} files ({} already cached, {} for unknown repositories, {} rejected)", imported,
                alreadyCached, unknownRepository, rejected.size());
        return new ImportResult(imported, alreadyCached, unknownRepository, rejected);
    }

    private static boolean store(ArtifactStore store, ArtifactPath path, InputStream content, ArtifactMeta meta,
                                 String expectedSha256) throws IOException {
        try (ArtifactStore.PendingWrite write = store.begin(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                write.write(buffer, 0, read);
            }
            if (expectedSha256 != null && !expectedSha256.equals(write.checksums().sha256())) {
                log.warn("Rejecting {}: its bytes do not match the bundle's checksum", path.value());
                return false;
            }
            Origin origin = meta == null ? new Origin(null, null, null)
                    : new Origin(meta.upstreamUrl(), meta.etag(), meta.lastModified());
            write.commit(origin, meta == null ? null : meta.checksumVerified());
            return true;
        }
    }

    private List<Selected> select(Selection selection) {
        String prefix = selection.path() == null || selection.path().isBlank() ? null
                : ArtifactPath.of(selection.path()).value();
        List<Selected> selected = new ArrayList<>();
        for (Map.Entry<Repository, List<CachedArtifact>> entry : service.list().entrySet()) {
            if (selection.repository() != null && !selection.repository().equals(entry.getKey().name())) {
                continue;
            }
            for (CachedArtifact artifact : entry.getValue()) {
                String value = artifact.path().value();
                if (prefix != null && !value.equals(prefix) && !value.startsWith(prefix + "/")) {
                    continue;
                }
                if (selection.usedSince() != null) {
                    Instant lastAccess = stats.access(entry.getKey().name() + "/" + value).lastAccess();
                    if (lastAccess == null || lastAccess.isBefore(selection.usedSince())) {
                        continue;
                    }
                }
                selected.add(new Selected(entry.getKey(), artifact));
            }
        }
        return selected;
    }

    /** Jackson closes the stream it writes to; a zip entry must stay open. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
