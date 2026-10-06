package com.localrepo.server.artifact;

/** Where a downloaded artifact came from, as reported by the upstream response. */
public record Origin(String url, String etag, String lastModified) {
}
