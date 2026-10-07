package com.localrepo.server.api;

import com.localrepo.server.artifact.ArtifactPath;

import java.util.Arrays;

/** Maven coordinates read back from a repository path; parts that the path does not have are null. */
public record MavenCoordinates(String group, String artifact, String version, String file) {

    public static MavenCoordinates of(ArtifactPath path) {
        String[] segments = path.value().split("/");
        String file = segments[segments.length - 1];
        if (file.startsWith("maven-metadata.xml") && segments.length >= 3) {
            // <group>/<artifact>/maven-metadata.xml, or the same below a -SNAPSHOT version
            boolean snapshot = segments[segments.length - 2].endsWith("-SNAPSHOT") && segments.length >= 4;
            int artifactIndex = segments.length - (snapshot ? 3 : 2);
            return new MavenCoordinates(group(segments, artifactIndex), segments[artifactIndex],
                    snapshot ? segments[segments.length - 2] : null, file);
        }
        if (segments.length >= 4) {
            return new MavenCoordinates(group(segments, segments.length - 3), segments[segments.length - 3],
                    segments[segments.length - 2], file);
        }
        return new MavenCoordinates(null, null, null, file);
    }

    private static String group(String[] segments, int artifactIndex) {
        return String.join(".", Arrays.copyOfRange(segments, 0, artifactIndex));
    }
}
