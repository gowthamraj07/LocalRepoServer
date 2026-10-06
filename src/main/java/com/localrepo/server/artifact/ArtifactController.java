package com.localrepo.server.artifact;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class ArtifactController {

    private static final String CACHE_PREFIX = "/cache/";

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    /** GET and (implicitly) HEAD for any artifact below {@code /cache/}. */
    @GetMapping(CACHE_PREFIX + "**")
    public ResponseEntity<Resource> artifact(HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length() + CACHE_PREFIX.length());
        ArtifactPath path = ArtifactPath.of(raw);

        return service.resolve(path)
                .map(artifact -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(ContentTypes.of(path));
                    ArtifactMeta meta = artifact.meta();
                    if (meta.etag() != null) {
                        response.header(HttpHeaders.ETAG, meta.etag());
                    }
                    if (meta.lastModified() != null) {
                        response.header(HttpHeaders.LAST_MODIFIED, meta.lastModified());
                    } else {
                        response.lastModified(meta.fetchedAt());
                    }
                    return response.<Resource>body(new FileSystemResource(artifact.file()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/list")
    public List<ArtifactSummary> list() {
        return service.list().stream()
                .map(a -> new ArtifactSummary(a.path().value(), a.meta().upstreamUrl(), a.meta().size(),
                        a.meta().fetchedAt()))
                .toList();
    }

    @ExceptionHandler(InvalidArtifactPathException.class)
    ResponseEntity<String> invalidPath(InvalidArtifactPathException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    public record ArtifactSummary(String path, String upstreamUrl, long size, Instant fetchedAt) {
    }
}
