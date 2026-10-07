package com.localrepo.server.maintenance;

import com.localrepo.server.api.AccessStats;
import com.localrepo.server.artifact.ArtifactService;
import com.localrepo.server.config.LocalRepoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
public class MaintenanceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceConfiguration.class);

    @Bean
    CacheEvictor cacheEvictor(ArtifactService service, AccessStats stats, LocalRepoProperties properties) {
        long maxBytes = properties.maxSize() == null ? 0 : properties.maxSize().toBytes();
        return new CacheEvictor(service, stats, maxBytes, properties.pinned());
    }

    /** Enforces the size limit periodically, when there is one. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService maintenanceScheduler(CacheEvictor evictor, LocalRepoProperties properties) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("maintenance").factory());
        if (properties.maxSize() != null) {
            long every = properties.evictionInterval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    evictor.evict();
                } catch (Exception e) {
                    log.warn("Eviction failed", e);
                }
            }, every, every, TimeUnit.MILLISECONDS);
        }
        return scheduler;
    }
}
