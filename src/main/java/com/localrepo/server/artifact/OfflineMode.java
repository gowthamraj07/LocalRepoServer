package com.localrepo.server.artifact;

import java.util.concurrent.atomic.AtomicBoolean;

/** When on, the server never contacts an upstream: cached artifacts are served, even stale ones, and misses are 404. */
public class OfflineMode {

    private final AtomicBoolean enabled;

    public OfflineMode(boolean enabled) {
        this.enabled = new AtomicBoolean(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void set(boolean enabled) {
        this.enabled.set(enabled);
    }
}
