package com.localrepo.server.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.localrepo.server.domain.DependencyDomain;

import java.net.URI;
import java.util.List;

public class DependencyRepository {

    private static final Logger log = LoggerFactory.getLogger(DependencyRepository.class);
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

        return crudRepository.save(domain).getId().toString();
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

            log.debug("No record for {}, using {}", path, domain);

            return domain;
        }

        return domainList.get(0);
    }

    public void delete(DependencyDomain domain) {
        crudRepository.delete(domain);
    }

    public void deleteWithNull() {
        crudRepository.deleteWithNull();
    }
}
