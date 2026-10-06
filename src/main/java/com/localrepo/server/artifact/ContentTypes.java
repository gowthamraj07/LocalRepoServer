package com.localrepo.server.artifact;

import org.springframework.http.MediaType;

final class ContentTypes {

    private static final MediaType JAVA_ARCHIVE = MediaType.parseMediaType("application/java-archive");

    private ContentTypes() {
    }

    static MediaType of(ArtifactPath path) {
        String name = path.fileName();
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "pom", "xml" -> MediaType.APPLICATION_XML;
            case "jar", "aar", "war", "klib" -> JAVA_ARCHIVE;
            case "module", "json" -> MediaType.APPLICATION_JSON;
            case "md5", "sha1", "sha256", "sha512", "asc" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
