package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;

import java.net.URI;
import java.util.List;

public class DependencyRepository {
    private DependencyCrudRepository crudRepository;

    public DependencyRepository(DependencyCrudRepository crudRepository) {
        this.crudRepository = crudRepository;
    }


    public synchronized String getId(DependencyDomain domain) {
        if (crudRepository == null) {
            return "-1";
        }

        List<DependencyDomain> domains = crudRepository.findByPath(domain.getPath());
        for (DependencyDomain dependencyDomain : domains) {
            if (dependencyDomain.equals(domain)) {
                return dependencyDomain.getId().toString();
            }
        }

        long nextId = crudRepository.getMaxId() + 1;
        domain.setId(nextId);
        crudRepository.save(domain);
        return "" + nextId;
    }

    public List<DependencyDomain> list() {
        return crudRepository.findAll();
    }

    public void update(DependencyDomain domain) {
        crudRepository.save(domain);
    }

    public DependencyDomain findDomainByPath(String path) {
        List<DependencyDomain> domainList = crudRepository.findByPath(path);

        if (domainList.isEmpty()) {
            DependencyDomain domain = new DependencyDomain();
            URI uri = URI.create(path);
            domain.setHost(uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort());
            domain.setRequestedPath(uri.getPath());
            return domain;
        }

        DependencyDomain dependencyDomain = domainList.get(0);
        return dependencyDomain;
    }

    public void delete(DependencyDomain domain) {
        crudRepository.delete(domain);
    }
}
