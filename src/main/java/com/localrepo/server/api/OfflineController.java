package com.localrepo.server.api;

import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.setup.SetupController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Switches offline mode at runtime. Changes need the same action header as setup changes. */
@RestController
public class OfflineController {

    private final OfflineMode offline;

    public OfflineController(OfflineMode offline) {
        this.offline = offline;
    }

    public record State(boolean enabled) {
    }

    @GetMapping("/api/offline")
    public State state() {
        return new State(offline.isEnabled());
    }

    @PostMapping("/api/offline")
    public State change(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action,
                        @RequestBody State requested) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
        offline.set(requested.enabled());
        return state();
    }
}
