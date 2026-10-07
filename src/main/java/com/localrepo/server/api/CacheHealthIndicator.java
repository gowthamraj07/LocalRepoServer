package com.localrepo.server.api;

import com.localrepo.server.artifact.CacheLocation;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Down while the cache directory is unavailable, typically because its disk is not connected. The Gradle init script
 * then leaves builds to their own repositories instead of sending them to a server that cannot cache anything.
 */
public class CacheHealthIndicator implements HealthIndicator {

    private final CacheLocation location;

    public CacheHealthIndicator(CacheLocation location) {
        this.location = location;
    }

    @Override
    public Health health() {
        Health.Builder health = location.available() ? Health.up() : Health.down()
                .withDetail("reason", "the cache directory is missing or not writable; is its disk connected?");
        return health.withDetail("cacheDir", location.dir().toString())
                .withDetail("freeSpace", location.freeSpace())
                .build();
    }
}
