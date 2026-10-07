package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.DownloadCoordinator;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.FreshnessPolicy;
import com.localrepo.server.artifact.NegativeCache;
import com.localrepo.server.artifact.OfflineMode;
import com.localrepo.server.artifact.Repository;
import com.localrepo.server.artifact.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(LocalRepoProperties.class)
public class LocalRepoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LocalRepoConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UpstreamClient upstreamClient(LocalRepoProperties properties) {
        return new UpstreamClient(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), properties.readIdleTimeout());
    }

    @Bean
    DownloadTracker downloadTracker(Clock clock) {
        return new DownloadTracker(clock);
    }

    @Bean
    DownloadCoordinator downloadCoordinator(Clock clock, LocalRepoProperties properties, DownloadTracker tracker) {
        return new DownloadCoordinator(clock, properties.readIdleTimeout(), tracker);
    }

    @Bean
    NegativeCache negativeCache(LocalRepoProperties properties, Clock clock) {
        return new NegativeCache(properties.negativeCacheTtl(), clock);
    }

    @Bean
    OfflineMode offlineMode(LocalRepoProperties properties) {
        return new OfflineMode(properties.offline());
    }

    @Bean
    ArtifactService artifactService(LocalRepoProperties properties, Environment environment, Clock clock,
                                    UpstreamClient upstreamClient, NegativeCache negativeCache,
                                    DownloadCoordinator downloads, OfflineMode offline) {
        List<Repository> repositories = upstreams(properties, environment).stream()
                .map(upstream -> new Repository(upstream.name(), upstream.url(), upstream.includes(),
                        upstream.excludes(), credentials(upstream, environment),
                        new ArtifactStore(properties.cacheDir().resolve(upstream.name()), clock)))
                .toList();
        repositories.forEach(r -> log.info("Upstream {}", r));
        return new ArtifactService(repositories, upstreamClient, negativeCache, downloads,
                new FreshnessPolicy(properties.metadataTtl(), clock), offline);
    }

    /** {@code --repos=url1,url2} replaces the configured upstreams with unfiltered ones named after their hosts. */
    static List<LocalRepoProperties.Upstream> upstreams(LocalRepoProperties properties, Environment environment) {
        String legacy = environment.getProperty("repos");
        if (legacy == null || legacy.isBlank()) {
            List<LocalRepoProperties.Upstream> upstreams = properties.upstreams();
            for (int i = 0; i < upstreams.size(); i++) {
                LocalRepoProperties.Upstream upstream = upstreams.get(i);
                if (upstream.name() == null || upstream.url() == null) {
                    // Spring replaces a list as a whole, so setting only upstreams[i].url drops every other field.
                    throw new IllegalArgumentException("localrepo.upstreams[" + i + "] needs a name and a url (when "
                            + "overriding one field of an upstream, give its name and url too)");
                }
            }
            return upstreams;
        }
        Set<String> names = new HashSet<>();
        List<LocalRepoProperties.Upstream> upstreams = new ArrayList<>();
        for (String url : Arrays.stream(legacy.split(",")).map(String::trim).filter(u -> !u.isEmpty()).toList()) {
            String base = URI.create(url).getHost().toLowerCase().replaceAll("[^a-z0-9]+", "-");
            String name = base;
            for (int i = 2; !names.add(name); i++) {
                name = base + "-" + i;
            }
            upstreams.add(new LocalRepoProperties.Upstream(name, url, null, null, null));
        }
        return upstreams;
    }

    private static Repository.Credentials credentials(LocalRepoProperties.Upstream upstream, Environment environment) {
        LocalRepoProperties.Credentials names = upstream.credentials();
        if (names.tokenEnv() != null) {
            return Repository.Credentials.bearer(required(upstream, names.tokenEnv(), environment));
        }
        if (names.usernameEnv() != null || names.passwordEnv() != null) {
            return Repository.Credentials.basic(required(upstream, names.usernameEnv(), environment),
                    required(upstream, names.passwordEnv(), environment));
        }
        return Repository.Credentials.NONE;
    }

    private static String required(LocalRepoProperties.Upstream upstream, String variable, Environment environment) {
        String value = variable == null ? null : environment.getProperty(variable);
        if (value == null) {
            log.warn("Upstream {} expects credentials in {}, which is not set; sending none", upstream.name(), variable);
        }
        return value;
    }
}
