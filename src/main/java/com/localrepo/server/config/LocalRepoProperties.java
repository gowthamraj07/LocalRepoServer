package com.localrepo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * @param home             LocalRepoServer's own directory; config.yml and upstreams.yml there are applied on start
 * @param upstreams        remote repositories; the group tries those whose filters accept a path, in this order
 * @param extraUpstreams   more upstreams, tried after {@code upstreams}: add private repositories here without
 *                         repeating the defaults
 * @param cacheDir         root of the on-disk cache, one directory per upstream
 * @param negativeCacheTtl how long a path that every upstream reported missing is answered with 404 without asking
 *                         again; zero disables it
 * @param connectTimeout   how long to wait for an upstream connection
 * @param readIdleTimeout  how long an upstream may go without sending anything, for headers or body, before the
 *                         fetch is abandoned. There is no limit on the total download time.
 * @param gradleUserHome   where the Gradle init script is installed ({@code init.d} below it)
 * @param mavenSettings    the user's Maven settings file that the mirror is installed into
 * @param metadataTtl      how long version listings and snapshots are served before checking the upstream again
 * @param offline          start in offline mode: never contact an upstream
 * @param maxSize          size the cache is kept under by deleting the least recently used files; unset means no limit
 * @param pinned           Ant-style path patterns that are never evicted
 * @param evictionInterval how often the size limit is enforced
 */
@ConfigurationProperties("localrepo")
public record LocalRepoProperties(Path home, List<Upstream> upstreams, List<Upstream> extraUpstreams, Path cacheDir, Duration negativeCacheTtl,
                                  Duration connectTimeout, Duration readIdleTimeout, Path gradleUserHome,
                                  Path mavenSettings, Duration metadataTtl, boolean offline, DataSize maxSize,
                                  List<String> pinned, Duration evictionInterval) {

    public LocalRepoProperties {
        home = home == null ? Path.of(System.getProperty("user.home"), ".localrepo") : home;
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        extraUpstreams = extraUpstreams == null ? List.of() : List.copyOf(extraUpstreams);
        cacheDir = cacheDir == null ? Path.of(System.getProperty("user.home"), ".localrepo", "cache") : cacheDir;
        negativeCacheTtl = negativeCacheTtl == null ? Duration.ofMinutes(5) : negativeCacheTtl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readIdleTimeout = readIdleTimeout == null ? Duration.ofSeconds(60) : readIdleTimeout;
        gradleUserHome = gradleUserHome == null ? Path.of(System.getProperty("user.home"), ".gradle") : gradleUserHome;
        mavenSettings = mavenSettings == null ? Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
                : mavenSettings;
        metadataTtl = metadataTtl == null ? Duration.ofHours(24) : metadataTtl;
        pinned = pinned == null ? List.of() : List.copyOf(pinned);
        evictionInterval = evictionInterval == null ? Duration.ofMinutes(10) : evictionInterval;
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
            credentials = credentials == null ? Credentials.NONE : credentials;
        }
    }

    /**
     * Where to find credentials, never the credentials themselves: a token (sent as Bearer) or a username and password
     * (sent as Basic), each named either as an environment variable ({@code *-env}) or as a property in the Gradle
     * user home's {@code gradle.properties} ({@code gradle-property-*}), where Gradle users keep them already.
     */
    public record Credentials(String usernameEnv, String passwordEnv, String tokenEnv, String gradlePropertyUsername,
                              String gradlePropertyPassword, String gradlePropertyToken) {

        public static final Credentials NONE = new Credentials(null, null, null, null, null, null);
    }
}
