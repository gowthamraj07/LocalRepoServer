package com.localrepo.server.ui;

import com.localrepo.server.api.ApiController;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.CacheVerifier;
import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.artifact.Repository;
import com.localrepo.server.setup.GradleSetup;
import com.localrepo.server.setup.MavenSetup;
import com.localrepo.server.setup.SetupController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The web UI: server-rendered pages with htmx fragments. Every page sends {@value SetupController#ACTION_HEADER} on
 * its htmx requests, so the same guard as the JSON API protects the buttons here.
 */
@Controller
public class UiController {

    private static final int PAGE_SIZE = 50;

    private final ApiController api;
    private final ArtifactService service;
    private final OfflineMode offline;
    private final GradleSetup gradle;
    private final MavenSetup maven;
    private final CacheVerifier verifier;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public UiController(ApiController api, ArtifactService service, OfflineMode offline, GradleSetup gradle,
                        MavenSetup maven, CacheVerifier verifier) {
        this.api = api;
        this.service = service;
        this.offline = offline;
        this.gradle = gradle;
        this.maven = maven;
        this.verifier = verifier;
    }

    @ModelAttribute("offline")
    boolean offline() {
        return offline.isEnabled();
    }

    // --- pages ---

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("stats", api.stats());
        model.addAttribute("downloads", api.downloads());
        return "dashboard";
    }

    @GetMapping("/downloads")
    public String downloads(Model model) {
        model.addAttribute("downloads", api.downloads());
        return "downloads";
    }

    @GetMapping("/artifacts")
    public String artifacts(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                            Model model) {
        model.addAttribute("q", q);
        model.addAttribute("artifacts", api.artifacts(q, null, page, PAGE_SIZE));
        return "artifacts";
    }

    @GetMapping("/upstreams")
    public String upstreams(Model model) {
        List<Repository> repositories = service.repositories();
        model.addAttribute("repositories", repositories);
        model.addAttribute("counts", service.list().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), e -> e.getValue().size())));
        return "upstreams";
    }

    @GetMapping("/setup")
    public String setup(Model model) throws IOException {
        String baseUrl = baseUrl();
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("gradle", gradle.status(baseUrl));
        model.addAttribute("maven", maven.status(baseUrl));
        model.addAttribute("verify", verifier.report());
        return "setup";
    }

    // --- fragments ---

    @GetMapping("/ui/fragments/stats")
    public String statsFragment(Model model) {
        model.addAttribute("stats", api.stats());
        return "fragments/stats :: stats";
    }

    @GetMapping("/ui/fragments/downloads")
    public String downloadsFragment(Model model) {
        model.addAttribute("downloads", api.downloads());
        return "fragments/downloads :: downloads";
    }

    @GetMapping("/ui/fragments/artifacts")
    public String artifactsFragment(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("q", q);
        model.addAttribute("artifacts", api.artifacts(q, null, page, PAGE_SIZE));
        return "fragments/artifacts :: artifacts";
    }

    @GetMapping("/ui/fragments/upstream-status")
    @ResponseBody
    public String upstreamStatus(@RequestParam String name) {
        Repository repository = service.repositories().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (offline.isEnabled()) {
            return "<span class=\"badge muted\">Offline mode</span>";
        }
        long started = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(repository.url() + "/"))
                    .timeout(Duration.ofSeconds(5)).GET();
            repository.authorization().ifPresent(value -> request.header("Authorization", value));
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            long millis = (System.nanoTime() - started) / 1_000_000;
            return "<span class=\"badge ok\">Reachable</span> <span class=\"muted\">HTTP " + status + ", "
                    + millis + " ms</span>";
        } catch (IOException e) {
            return "<span class=\"badge bad\">Unreachable</span>";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "<span class=\"badge bad\">Unreachable</span>";
        }
    }

    @GetMapping("/ui/fragments/verify")
    public String verifyFragment(Model model) {
        model.addAttribute("verify", verifier.report());
        return "fragments/setup :: verify";
    }

    // --- actions ---

    @PostMapping("/ui/offline")
    public String toggleOffline(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                                Model model) {
        requireAction(action);
        offline.set(!offline.isEnabled());
        model.addAttribute("offline", offline.isEnabled());
        return "fragments/layout :: offline-switch";
    }

    @PostMapping("/ui/setup/{tool}/{change}")
    public String changeSetup(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                              @org.springframework.web.bind.annotation.PathVariable String tool,
                              @org.springframework.web.bind.annotation.PathVariable String change,
                              Model model) throws IOException {
        requireAction(action);
        String baseUrl = baseUrl();
        boolean install = switch (change) {
            case "install" -> true;
            case "uninstall" -> false;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        model.addAttribute("baseUrl", baseUrl);
        switch (tool) {
            case "gradle" -> {
                model.addAttribute("gradle", install ? gradle.install(baseUrl) : gradle.uninstall(baseUrl));
                return "fragments/setup :: gradle";
            }
            case "maven" -> {
                try {
                    model.addAttribute("maven", install ? maven.install(baseUrl) : maven.uninstall(baseUrl));
                } catch (IllegalArgumentException e) {
                    model.addAttribute("maven", maven.status(baseUrl));
                    model.addAttribute("mavenError", e.getMessage());
                }
                return "fragments/setup :: maven";
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/ui/verify")
    public String startVerify(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                              Model model) {
        requireAction(action);
        model.addAttribute("verify", verifier.start());
        return "fragments/setup :: verify";
    }

    @PostMapping("/ui/artifacts/delete")
    @ResponseBody
    public String delete(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                         @RequestParam String repository, @RequestParam String path) throws IOException {
        api.delete(action, repository, path);
        return "";
    }

    @PostMapping("/ui/artifacts/refetch")
    @ResponseBody
    public String refetch(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                          @RequestParam String repository, @RequestParam String path) throws IOException {
        boolean started = api.refetch(action, repository, path).getStatusCode().is2xxSuccessful();
        return started ? "<span class=\"badge ok\">Refetching</span>" : "<span class=\"badge bad\">Not found</span>";
    }

    private static void requireAction(String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
    }
}
