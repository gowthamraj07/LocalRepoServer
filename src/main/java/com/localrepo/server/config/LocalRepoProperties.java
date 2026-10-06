package com.localrepo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("localrepo")
public record LocalRepoProperties(List<String> upstreams) {

    public LocalRepoProperties {
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
    }
}
