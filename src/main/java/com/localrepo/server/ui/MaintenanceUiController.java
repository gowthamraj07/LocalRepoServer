package com.localrepo.server.ui;

import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.config.LocalRepoProperties;
import com.localrepo.server.maintenance.CacheBundles;
import com.localrepo.server.maintenance.CacheEvictor;
import com.localrepo.server.maintenance.CachePurger;
import com.localrepo.server.maintenance.Prefetcher;
import com.localrepo.server.setup.SetupController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/** The Maintenance page: bundles, prefetching, purging and the size limit. */
@Controller
public class MaintenanceUiController {

    private final CacheBundles bundles;
    private final Prefetcher prefetcher;
    private final CachePurger purger;
    private final CacheEvictor evictor;
    private final OfflineMode offline;
    private final LocalRepoProperties properties;

    public MaintenanceUiController(CacheBundles bundles, Prefetcher prefetcher, CachePurger purger,
                                   CacheEvictor evictor, OfflineMode offline, LocalRepoProperties properties) {
        this.bundles = bundles;
        this.prefetcher = prefetcher;
        this.purger = purger;
        this.evictor = evictor;
        this.offline = offline;
        this.properties = properties;
    }

    @ModelAttribute("offline")
    boolean offline() {
        return offline.isEnabled();
    }

    @GetMapping("/maintenance")
    public String page(Model model) {
        model.addAttribute("prefetch", prefetcher.report());
        model.addAttribute("maxSize", properties.maxSize());
        model.addAttribute("pinned", properties.pinned());
        return "maintenance";
    }

    @PostMapping("/ui/import")
    public String importBundle(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                               @RequestParam("file") MultipartFile file, Model model) throws IOException {
        requireAction(action);
        try (InputStream in = file.getInputStream()) {
            model.addAttribute("result", bundles.importBundle(in));
        }
        return "fragments/maintenance :: import-result";
    }

    @PostMapping("/ui/prefetch")
    public String prefetch(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                           @RequestParam String list, Model model) {
        requireAction(action);
        try {
            model.addAttribute("prefetch", prefetcher.start(list));
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("prefetch", prefetcher.report());
            model.addAttribute("prefetchError", e.getMessage());
        }
        return "fragments/maintenance :: prefetch";
    }

    @GetMapping("/ui/fragments/prefetch")
    public String prefetchFragment(Model model) {
        model.addAttribute("prefetch", prefetcher.report());
        return "fragments/maintenance :: prefetch";
    }

    @PostMapping("/ui/purge")
    public String purge(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                        @RequestParam(required = false) String path,
                        @RequestParam(required = false) Integer unusedDays, Model model) throws IOException {
        requireAction(action);
        try {
            String prefix = path == null || path.isBlank() ? null : path.strip();
            model.addAttribute("purged", purger.purge(prefix, unusedDays == null ? null : Duration.ofDays(unusedDays)));
        } catch (IllegalArgumentException e) {
            model.addAttribute("purgeError", e.getMessage());
        }
        return "fragments/maintenance :: purge-result";
    }

    @PostMapping("/ui/evict")
    public String evict(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                        Model model) throws IOException {
        requireAction(action);
        model.addAttribute("eviction", evictor.evict());
        return "fragments/maintenance :: evict-result";
    }

    private static void requireAction(String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
    }
}
