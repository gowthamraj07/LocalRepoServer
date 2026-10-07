package com.localrepo.server.api;

import com.localrepo.server.artifact.CacheLocation;
import com.localrepo.server.artifact.CachedArtifact;
import com.localrepo.server.artifact.Download;
import com.localrepo.server.artifact.DownloadTracker;
import com.localrepo.server.artifact.RequestListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ApiConfiguration {

    @Bean(destroyMethod = "close")
    AccessStats accessStats(CacheLocation location, Clock clock, DownloadTracker tracker) {
        AccessStats stats = new AccessStats(location.dir(), clock);
        stats.startPeriodicFlush(Duration.ofMinutes(1));
        tracker.addListener(new DownloadTracker.Listener() {
            @Override
            public void finished(Download download) {
                if (download.state() == Download.State.COMPLETED) {
                    stats.downloaded(download.transferred());
                    try {
                        download.awaitResult().ifPresent(artifact -> stats.touch(artifact.file()));
                    } catch (java.io.IOException ignored) {
                        // completed, so there is nothing to wait for
                    }
                }
            }
        });
        return stats;
    }

    @Bean
    CacheHealthIndicator cacheHealthIndicator(CacheLocation location) {
        return new CacheHealthIndicator(location);
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
