package com.localrepo.server.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class UpstreamClient {

    private final HttpClient http;

    public UpstreamClient(HttpClient http) {
        this.http = http;
    }

    /** Opens a GET for {@code path} below {@code baseUrl}. The caller must close the returned response. */
    public UpstreamResponse get(String baseUrl, ArtifactPath path) throws IOException {
        String url = stripTrailingSlash(baseUrl) + "/" + path.value();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new UpstreamResponse(url, response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching " + url);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
