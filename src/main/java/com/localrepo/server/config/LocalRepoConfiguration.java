package com.localrepo.server.config;

import com.localrepo.server.domain.Repositories;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

@Configuration
@EnableConfigurationProperties(LocalRepoProperties.class)
public class LocalRepoConfiguration {

    @Bean
    Repositories repositories(LocalRepoProperties properties) {
        Repositories repositories = new Repositories();
        repositories.setRepos(new ArrayList<>(properties.upstreams()));
        return repositories;
    }
}
