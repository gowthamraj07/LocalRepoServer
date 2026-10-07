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
    private final Duration retryDelay;

    /**
     * @param responseTimeout how long to wait for the response headers once connected
     * @param retryDelay      how long to wait before asking again after a broken connection or a server error
     */
    public UpstreamClient(HttpClient http, Duration responseTimeout, Duration retryDelay) {
        this.http = http;
        this.responseTimeout = responseTimeout;
        this.retryDelay = retryDelay;
    }

    /** Opens a GET for {@code path} in {@code repository}. The caller must close the returned response. */
    public UpstreamResponse get(Repository repository, ArtifactPath path) throws IOException {
        return get(repository, path, null);
    }

    /**
     * Like {@link #get(Repository, ArtifactPath)}, but conditional on the validators of a copy we already have, so an
     * unchanged file answers 304 without a body.
     * <p>
     * A broken connection or a server error is often momentary, so it is tried once more before being reported.
     */
    public UpstreamResponse get(Repository repository, ArtifactPath path, ArtifactMeta cached) throws IOException {
        UpstreamResponse first;
        try {
            first = send(repository, path, cached);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            pause(e);
            return send(repository, path, cached);
        }
        if (!first.isServerError()) {
            return first;
        }
        first.close();
        pause(null);
        return send(repository, path, cached);
    }

    private void pause(IOException cause) throws InterruptedIOException {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted before retrying");
            interrupted.initCause(cause);
            throw interrupted;
        }
    }

    private UpstreamResponse send(Repository repository, ArtifactPath path, ArtifactMeta cached) throws IOException {
        String url = repository.url() + "/" + path.value();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(responseTimeout).GET();
        repository.authorization().ifPresent(value -> request.header("Authorization", value));
        if (cached != null && cached.etag() != null) {
            request.header("If-None-Match", cached.etag());
        }
        if (cached != null && cached.lastModified() != null) {
            request.header("If-Modified-Since", cached.lastModified());
        }
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new UpstreamResponse(url, response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching " + url);
        }
    }
}
