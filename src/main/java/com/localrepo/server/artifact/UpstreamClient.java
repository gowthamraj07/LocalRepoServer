package com.localrepo.server.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpstreamClient {

    private final HttpClient http;
    private final Duration responseTimeout;

    /** @param responseTimeout how long to wait for the response headers once connected */
    public UpstreamClient(HttpClient http, Duration responseTimeout) {
        this.http = http;
        this.responseTimeout = responseTimeout;
    }

    /** Opens a GET for {@code path} in {@code repository}. The caller must close the returned response. */
    public UpstreamResponse get(Repository repository, ArtifactPath path) throws IOException {
        String url = repository.url() + "/" + path.value();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(responseTimeout).GET();
        repository.authorization().ifPresent(value -> request.header("Authorization", value));
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new UpstreamResponse(url, response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching " + url);
        }
    }
}
