package com.localrepo.server.api;

import com.localrepo.server.artifact.CacheVerifier;
import com.localrepo.server.setup.SetupController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VerifyController {

    private final CacheVerifier verifier;

    public VerifyController(CacheVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/api/verify")
    public CacheVerifier.Report report() {
        return verifier.report();
    }

    /** Starts re-checking the whole cache; poll GET for the result. */
    @PostMapping("/api/verify")
    public CacheVerifier.Report start(@RequestHeader(value = SetupController.ACTION_HEADER, required = false) String action) {
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + SetupController.ACTION_HEADER + " header");
        }
        return verifier.start();
    }
}
