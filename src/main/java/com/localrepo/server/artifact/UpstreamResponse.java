package com.localrepo.server.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;

public record UpstreamResponse(String url, int status, HttpHeaders headers, InputStream body) implements AutoCloseable {

    public boolean isOk() {
        return status == 200;
    }

    public boolean isNotModified() {
        return status == 304;
    }

    public Origin origin() {
        return new Origin(url, headers.firstValue("ETag").orElse(null),
                headers.firstValue("Last-Modified").orElse(null));
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
