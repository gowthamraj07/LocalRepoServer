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
                        upstream.excludes(), credentials(upstream, environment, properties.gradleUserHome()),
                        new ArtifactStore(properties.cacheDir().resolve(upstream.name()), clock)))
                .toList();
        repositories.forEach(r -> log.info("Upstream {}", r));
        return new ArtifactService(repositories, upstreamClient, negativeCache, downloads,
                new FreshnessPolicy(properties.metadataTtl(), clock), offline);
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

    private static Repository.Credentials credentials(LocalRepoProperties.Upstream upstream, Environment environment,
                                                      Path gradleUserHome) {
        LocalRepoProperties.Credentials names = upstream.credentials();
        CredentialSource source = new CredentialSource(upstream, environment, gradleUserHome);
        String token = source.first(names.tokenEnv(), names.gradlePropertyToken());
        if (names.tokenEnv() != null || names.gradlePropertyToken() != null) {
            return Repository.Credentials.bearer(token);
        }
        if (names.usernameEnv() != null || names.passwordEnv() != null || names.gradlePropertyUsername() != null
                || names.gradlePropertyPassword() != null) {
            return Repository.Credentials.basic(source.first(names.usernameEnv(), names.gradlePropertyUsername()),
                    source.first(names.passwordEnv(), names.gradlePropertyPassword()));
        }
        return Repository.Credentials.NONE;
    }

    /** Looks a credential up by environment variable or by Gradle property, warning when it is not set. */
    private record CredentialSource(LocalRepoProperties.Upstream upstream, Environment environment, Path gradleUserHome) {

        String first(String env, String gradleProperty) {
            if (env != null) {
                return required(upstream, env, environment);
            }
            if (gradleProperty == null) {
                return null;
            }
            Properties properties = new Properties();
            Path file = gradleUserHome.resolve("gradle.properties");
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                } catch (IOException e) {
                    log.warn("Could not read {}", file, e);
                }
            }
            String value = properties.getProperty(gradleProperty);
            if (value == null) {
                log.warn("Upstream {} expects credentials in {} of {}, which is not set; sending none", upstream.name(),
                        gradleProperty, file);
            }
            return value;
        }
    }

    private static String required(LocalRepoProperties.Upstream upstream, String variable, Environment environment) {
        String value = variable == null ? null : environment.getProperty(variable);
        if (value == null) {
            log.warn("Upstream {} expects credentials in {}, which is not set; sending none", upstream.name(), variable);
        }
        return value;
    }
}
