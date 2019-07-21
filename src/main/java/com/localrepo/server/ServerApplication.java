package com.localrepo.server;

import com.localrepo.server.domain.Repositories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@SpringBootApplication
@ComponentScan({"com.localrepo.server", "com.localrepo.server.repository"})
@EntityScan({"com.localrepo.server.domain"})
@EnableJpaRepositories({"com.localrepo.server.repository"})
public class ServerApplication implements ApplicationRunner {


    @Autowired
    Repositories repositories;

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        constructRepositoriesFromArgument(args);
    }

    private void constructRepositoriesFromArgument(ApplicationArguments args) {
        List<String> repos = args.getOptionValues("repos");

        if (repos == null) {
            System.out.println("No repository urls provided...");
            return;
        }

        if (repos.size() > 0) {
            String[] urls = repos.get(0).split(",");
            repos = new LinkedList<>();
            repos.addAll(Arrays.asList(urls));
        }

        for (String repo : repos) {
            System.out.println(repo);
        }

        repositories.setRepos(repos);
    }
}

