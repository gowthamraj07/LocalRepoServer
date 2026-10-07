package com.localrepo.server.artifact;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

@RestController
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    /**
     * GET and (implicitly) HEAD for any artifact in the group. A miss is streamed to the client while it downloads, so
     * the build sees progress instead of waiting for the whole file. {@code /cache/} is the original URL and stays.
     */
    @GetMapping({"/cache/**", "/group/**"})
    public ResponseEntity<?> fromGroup(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = pathWithinApplication(request);
        ArtifactPath path = ArtifactPath.of(uri.substring(uri.indexOf('/', 1) + 1));
        return respond(path, service.resolve(path), request, response);
    }

    /** Like {@link #fromGroup} but asks only the named repository. */
    @GetMapping("/repo/{name}/**")
    public ResponseEntity<?> fromRepository(@PathVariable String name, HttpServletRequest request,
                                            HttpServletResponse response) throws IOException {
        String prefix = "/repo/" + name + "/";
        ArtifactPath path = ArtifactPath.of(pathWithinApplication(request).substring(prefix.length()));
        return respond(path, service.resolve(name, path), request, response);
    }

    private ResponseEntity<?> respond(ArtifactPath path, ArtifactService.Resolution resolution,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean head = "HEAD".equals(request.getMethod());
        return switch (resolution) {
            case ArtifactService.Resolution.Cached cached -> cachedResponse(path, cached.artifact(), cached.stale());
            case ArtifactService.Resolution.Missing missing -> ResponseEntity.notFound().build();
            case ArtifactService.Resolution.Downloading downloading ->
                    streamingResponse(path, downloading.download(), head, response);
        };
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** Writes a download in progress straight to the servlet response; returns null once it has been written. */
    private ResponseEntity<?> streamingResponse(ArtifactPath path, Download download, boolean head,
                                                HttpServletResponse response) throws IOException {
        return switch (download.awaitHeaders()) {
            case COMPLETED -> cachedResponse(path, download.awaitResult().orElseThrow(), download.isStale());
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case FAILED, CONNECTING -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            case STREAMING -> {
                response.setStatus(HttpStatus.OK.value());
                // If the upstream breaks mid-body the client must see the transfer fail, not wait or get a short
                // file: closing the connection makes the missing bytes against Content-Length an error.
                response.setHeader(HttpHeaders.CONNECTION, "close");
                response.setContentType(ContentTypes.of(path).toString());
                if (download.contentLength() >= 0) {
                    response.setContentLengthLong(download.contentLength());
                }
                Origin origin = download.origin();
                if (origin.etag() != null) {
                    response.setHeader(HttpHeaders.ETAG, origin.etag());
                }
                if (origin.lastModified() != null) {
                    response.setHeader(HttpHeaders.LAST_MODIFIED, origin.lastModified());
                }
                if (!head) {
                    copy(download, response);
                }
                yield null;
            }
        };
    }

    private static void copy(Download download, HttpServletResponse response) throws IOException {
        try (InputStream in = download.openStream()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                try {
                    out.write(buffer, 0, read);
                    out.flush();
                } catch (IOException clientGone) {
                    // The download carries on without us and still fills the cache.
                    log.debug("Client stopped reading {}", download.path().value());
                    return;
                }
            }
        }
    }

    private static ResponseEntity<?> cachedResponse(ArtifactPath path, CachedArtifact artifact, boolean stale) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(ContentTypes.of(path));
        if (stale) {
            // RFC 7234 warn-code 110; the custom header is easier to spot in a build log.
            response.header(HttpHeaders.WARNING, "110 - \"Response is Stale\"");
            response.header("X-LocalRepo-Stale", "true");
        }
        ArtifactMeta meta = artifact.meta();
        withValidators(response, meta.etag(), meta.lastModified());
        if (meta.lastModified() == null) {
            response.lastModified(meta.fetchedAt());
        }
        return response.body(new FileSystemResource(artifact.file()));
    }

    private static void withValidators(ResponseEntity.BodyBuilder response, String etag, String lastModified) {
        if (etag != null) {
            response.header(HttpHeaders.ETAG, etag);
        }
        if (lastModified != null) {
            response.header(HttpHeaders.LAST_MODIFIED, lastModified);
        }
    }

    @GetMapping("/list")
    public List<ArtifactSummary> list() {
        return service.list().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(a -> new ArtifactSummary(entry.getKey().name(),
                        a.path().value(), a.meta().upstreamUrl(), a.meta().size(), a.meta().fetchedAt())))
                .toList();
    }

    @ExceptionHandler(InvalidArtifactPathException.class)
    ResponseEntity<String> invalidPath(InvalidArtifactPathException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    public record ArtifactSummary(String repository, String path, String upstreamUrl, long size, Instant fetchedAt) {
    }
}
