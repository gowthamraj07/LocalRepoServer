package com.localrepo.server.domain;

import java.util.LinkedList;
import java.util.List;

public class Repositories {
    private List<String> repos = new LinkedList<>();

    public void setRepos(List<String> repos) {
        this.repos = repos;
    }

    public List<String> getRepos() {
        return repos;
    }
}
