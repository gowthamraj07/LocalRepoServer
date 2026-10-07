package com.localrepo.server.maintenance;

import com.localrepo.server.setup.SetupController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Size limit, purging and bundles. Changes need the {@code X-LocalRepo-Action} header. */
@RestController
public class MaintenanceController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CacheEvictor evictor;
    private final CachePurger purger;
    private final CacheBundles bundles;
    private final Clock clock;

    public MaintenanceController(CacheEvictor evictor, CachePurger purger, CacheBundles bundles, Clock clock) {
        this.evictor = evictor;
        this.purger = purger;
        this.bundles = bundles;
        this.clock = clock;
    }

    @PostMapping("/api/evict")
    public CacheEvictor.Result evict(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action)
            throws IOException {
        requireAction(action);
        return evictor.evict();
    }

    /** {@code unusedFor} takes durations such as {@code 30d} or {@code 12h}. */
    @PostMapping("/api/purge")
    public Map<String, List<String>> purge(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                                           @RequestParam(required = false) String path,
                                           @RequestParam(required = false) Duration unusedFor) throws IOException {
        requireAction(action);
        return Map.of("purged", purger.purge(path, unusedFor));
    }

    /**
     * Downloads a zip of cached files. Narrow it with {@code repository}, {@code path}, and {@code usedSince} (an
     * instant) or {@code usedWithin} (a duration): build a project, then export what it used to take it elsewhere.
     */
    @GetMapping("/api/export")
    public void export(@RequestParam(required = false) String repository, @RequestParam(required = false) String path,
                       @RequestParam(required = false) Instant usedSince,
                       @RequestParam(required = false) Duration usedWithin,
                       HttpServletResponse response) throws IOException {
        Instant since = usedSince != null ? usedSince : usedWithin == null ? null : clock.instant().minus(usedWithin);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"localrepo-bundle-" + FILE_DATE.format(clock.instant()) + ".zip\"");
        bundles.export(new CacheBundles.Selection(repository, path, since), response.getOutputStream());
    }

    /** Takes the zip as the request body, or as a multipart upload named {@code file}. */
    @PostMapping("/api/import")
    public CacheBundles.ImportResult importBundle(
            @RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
            HttpServletRequest request) throws IOException {
        requireAction(action);
        if (request instanceof MultipartHttpServletRequest multipart) {
            MultipartFile file = multipart.getFile("file");
            if (file == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file part named 'file'");
            }
            try (InputStream in = file.getInputStream()) {
                return bundles.importBundle(in);
            }
        }
        return bundles.importBundle(request.getInputStream());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    private static void requireAction(String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
    }
}
