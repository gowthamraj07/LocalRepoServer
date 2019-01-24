package com.localrepo.server.callback;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.NetworkRepository;

public class NetworkCallback implements NetworkRepository.Callback {
    private DependencyRepository repository;

    public NetworkCallback(DependencyRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onError(String url, String message) {

    }

    @Override
    public void onSuccess(String urlPath) {
        repository.update(getDomain(urlPath));
    }

    protected DependencyDomain getDomain(String urlPath) {
        return repository.findDomainByPath(urlPath);
    }
}
