package com.localrepo.server.artifact;

/** Told how each artifact request was answered, for statistics and live events. */
public interface RequestListener {

    /** Answered from the cache. */
    default void hit(CachedArtifact artifact) {
    }

    /** Had to wait for (or join) a download from an upstream. */
    default void miss(ArtifactPath path) {
    }
}
