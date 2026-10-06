package com.localrepo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * @param upstreams        remote repositories tried in order on a cache miss
 * @param cacheDir         root of the on-disk cache
 * @param negativeCacheTtl how long a path that every upstream reported missing is answered with 404 without asking
 *                         again; zero disables it
 */
@ConfigurationProperties("localrepo")
public record LocalRepoProperties(List<String> upstreams, Path cacheDir, Duration negativeCacheTtl) {

    public LocalRepoProperties {
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        cacheDir = cacheDir == null ? Path.of(System.getProperty("user.home"), ".localrepo", "cache") : cacheDir;
        negativeCacheTtl = negativeCacheTtl == null ? Duration.ofMinutes(5) : negativeCacheTtl;
    }
}
