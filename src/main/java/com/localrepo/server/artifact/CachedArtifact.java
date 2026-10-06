package com.localrepo.server.artifact;

import java.nio.file.Path;

public record CachedArtifact(ArtifactPath path, Path file, ArtifactMeta meta) {
}
