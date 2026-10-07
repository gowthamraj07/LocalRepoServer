package com.localrepo.server.config;

import com.localrepo.server.artifact.ArtifactStore;
import com.localrepo.server.artifact.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;

/** Turns a configured upstream into a {@link Repository}: its cache directory and its credentials. */
public class RepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(RepositoryFactory.class);

    private final LocalRepoProperties properties;
    private final Environment environment;
    private final Clock clock;

    public RepositoryFactory(LocalRepoProperties properties, Environment environment, Clock clock) {
        this.properties = properties;
        this.environment = environment;
        this.clock = clock;
    }

    public Repository create(LocalRepoProperties.Upstream upstream) {
        return new Repository(upstream.name(), upstream.url(), upstream.includes(), upstream.excludes(),
                credentials(upstream), new ArtifactStore(properties.cacheDir().resolve(upstream.name()), clock));
    }

    private Repository.Credentials credentials(LocalRepoProperties.Upstream upstream) {
        LocalRepoProperties.Credentials names = upstream.credentials();
        CredentialSource source = new CredentialSource(upstream, environment, properties.gradleUserHome());
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
