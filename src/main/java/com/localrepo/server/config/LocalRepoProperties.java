package com.localrepo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * @param upstreams        remote repositories; the group tries those whose filters accept a path, in this order
 * @param cacheDir         root of the on-disk cache, one directory per upstream
 * @param negativeCacheTtl how long a path that every upstream reported missing is answered with 404 without asking
 *                         again; zero disables it
 * @param connectTimeout   how long to wait for an upstream connection
 * @param readIdleTimeout  how long an upstream may go without sending anything, for headers or body, before the
 *                         fetch is abandoned. There is no limit on the total download time.
 * @param gradleUserHome   where the Gradle init script is installed ({@code init.d} below it)
 * @param mavenSettings    the user's Maven settings file that the mirror is installed into
 */
@ConfigurationProperties("localrepo")
public record LocalRepoProperties(List<Upstream> upstreams, Path cacheDir, Duration negativeCacheTtl,
                                  Duration connectTimeout, Duration readIdleTimeout, Path gradleUserHome,
                                  Path mavenSettings) {

    public LocalRepoProperties {
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        cacheDir = cacheDir == null ? Path.of(System.getProperty("user.home"), ".localrepo", "cache") : cacheDir;
        negativeCacheTtl = negativeCacheTtl == null ? Duration.ofMinutes(5) : negativeCacheTtl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readIdleTimeout = readIdleTimeout == null ? Duration.ofSeconds(60) : readIdleTimeout;
        gradleUserHome = gradleUserHome == null ? Path.of(System.getProperty("user.home"), ".gradle") : gradleUserHome;
        mavenSettings = mavenSettings == null ? Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
                : mavenSettings;
    }

    /**
     * @param name        URL-safe name, used in {@code /repo/<name>/} and as the cache directory
     * @param includes    Ant-style path patterns the group asks this upstream for; empty means all
     * @param excludes    patterns the group never asks this upstream for
     * @param credentials names of environment variables holding credentials, never the credentials themselves
     */
    public record Upstream(String name, String url, List<String> includes, List<String> excludes,
                           Credentials credentials) {

        public Upstream {
            includes = includes == null ? List.of() : List.copyOf(includes);
            excludes = excludes == null ? List.of() : List.copyOf(excludes);
            credentials = credentials == null ? new Credentials(null, null, null) : credentials;
        }
    }

    /** Either {@code tokenEnv} (sent as Bearer) or {@code usernameEnv} + {@code passwordEnv} (sent as Basic). */
    public record Credentials(String usernameEnv, String passwordEnv, String tokenEnv) {
    }
}
