package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.DownloadCoordinator;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.NegativeCache;
import com.localrepo.server.artifact.UpstreamClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LocalRepoProperties.class)
public class LocalRepoConfiguration {

    /** Single namespace until per-upstream caches arrive. */
    static final String DEFAULT_REPOSITORY = "default";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ArtifactStore artifactStore(LocalRepoProperties properties, Clock clock) {
        return new ArtifactStore(properties.cacheDir().resolve(DEFAULT_REPOSITORY), clock);
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
    ArtifactService artifactService(ArtifactStore store, UpstreamClient upstreamClient, NegativeCache negativeCache,
                                    DownloadCoordinator downloads, LocalRepoProperties properties) {
        return new ArtifactService(store, upstreamClient, properties.upstreams(), negativeCache, downloads);
    }
}
