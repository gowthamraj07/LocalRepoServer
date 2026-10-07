package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.CacheVerifier;
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

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
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
                .build(), properties.readIdleTimeout(), Duration.ofSeconds(1));
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
    RepositoryFactory repositoryFactory(LocalRepoProperties properties, Environment environment, Clock clock) {
        return new RepositoryFactory(properties, environment, clock);
    }

    @Bean
    ArtifactService artifactService(LocalRepoProperties properties, Environment environment, Clock clock,
                                    RepositoryFactory repositories, UpstreamClient upstreamClient,
                                    NegativeCache negativeCache, DownloadCoordinator downloads, OfflineMode offline) {
        List<Repository> configured = upstreams(properties, environment).stream().map(repositories::create).toList();
        configured.forEach(r -> log.info("Upstream {}", r));
        return new ArtifactService(configured, upstreamClient, negativeCache, downloads,
                new FreshnessPolicy(properties.metadataTtl(), clock), offline);
    }

    @Bean
    UpstreamEditor upstreamEditor(LocalRepoProperties properties, ArtifactService service, RepositoryFactory repositories) {
        return new UpstreamEditor(properties.home().resolve("upstreams.yml"), properties.extraUpstreams(), service,
                repositories);
    }

    @Bean
    CacheVerifier cacheVerifier(ArtifactService service, Clock clock) {
        return new CacheVerifier(service, clock);
    }

    /** {@code --repos=url1,url2} replaces the configured upstreams with unfiltered ones named after their hosts. */
    static List<LocalRepoProperties.Upstream> upstreams(LocalRepoProperties properties, Environment environment) {
        String legacy = environment.getProperty("repos");
        if (legacy == null || legacy.isBlank()) {
            requireNameAndUrl("localrepo.upstreams", properties.upstreams());
            requireNameAndUrl("localrepo.extra-upstreams", properties.extraUpstreams());
            return java.util.stream.Stream.concat(properties.upstreams().stream(), properties.extraUpstreams().stream())
                    .toList();
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

    private static void requireNameAndUrl(String key, List<LocalRepoProperties.Upstream> upstreams) {
        for (int i = 0; i < upstreams.size(); i++) {
            if (upstreams.get(i).name() == null || upstreams.get(i).url() == null) {
                // Spring replaces a list as a whole, so setting only upstreams[i].url drops every other field.
                throw new IllegalArgumentException(key + "[" + i + "] needs a name and a url (when overriding one "
                        + "field of an upstream, give its name and url too)");
            }
        }
    }

}
