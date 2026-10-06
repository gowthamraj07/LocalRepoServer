package com.localrepo.server.config;

import com.localrepo.server.domain.Repositories;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalRepoPropertiesTest {

    @Nested
    @SpringBootTest(args = "--repos=https://a.example/maven2,https://b.example/m2")
    class LegacyReposArgument {
        @Autowired
        Repositories repositories;

        @Test
        void mapsCommaSeparatedReposArgumentToUpstreams() {
            assertEquals(List.of("https://a.example/maven2", "https://b.example/m2"), repositories.getRepos());
        }
    }

    @Nested
    @SpringBootTest(properties = "localrepo.upstreams=https://c.example/maven2")
    class UpstreamsProperty {
        @Autowired
        Repositories repositories;

        @Test
        void readsUpstreamsFromConfiguration() {
            assertEquals(List.of("https://c.example/maven2"), repositories.getRepos());
        }
    }

    @Nested
    @SpringBootTest
    class Defaults {
        @Autowired
        LocalRepoProperties properties;

        @Test
        void defaultsToMavenCentralAndGoogle() {
            assertEquals(List.of("https://repo.maven.apache.org/maven2", "https://dl.google.com/dl/android/maven2"),
                    properties.upstreams());
        }
    }
}
