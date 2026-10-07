package com.localrepo.server.api;

import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.Download;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.RequestListener;
import com.localrepo.server.config.LocalRepoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ApiConfiguration {

    @Bean(destroyMethod = "close")
    AccessStats accessStats(LocalRepoProperties properties, Clock clock, DownloadTracker tracker) {
        AccessStats stats = new AccessStats(properties.cacheDir(), clock);
        stats.startPeriodicFlush(Duration.ofMinutes(1));
        tracker.addListener(new DownloadTracker.Listener() {
            @Override
            public void finished(Download download) {
                if (download.state() == Download.State.COMPLETED) {
                    stats.downloaded(download.transferred());
                }
            }
        });
        return stats;
    }

    @Bean
    RequestListener accessStatsListener(AccessStats stats) {
        return new RequestListener() {
            @Override
            public void hit(CachedArtifact artifact) {
                stats.hit(artifact.file(), artifact.meta().size());
            }

            @Override
            public void miss(com.localrepo.server.artifact.ArtifactPath path) {
                stats.miss();
            }
        };
    }

    @Bean(destroyMethod = "close")
    EventStream eventStream(DownloadTracker tracker) {
        EventStream events = new EventStream(tracker, Duration.ofMillis(250));
        tracker.addListener(events);
        return events;
    }
}
