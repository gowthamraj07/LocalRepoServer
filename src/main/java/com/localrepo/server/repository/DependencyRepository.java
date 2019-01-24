package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;

import java.util.List;

public class DependencyRepository {
    private DependencyCrudRepository curdRepository;

    public DependencyRepository(DependencyCrudRepository curdRepository) {
        this.curdRepository = curdRepository;
    }


    public synchronized String getId(DependencyDomain domain) {
        if (curdRepository == null) {
            return "-1";
        }

        List<DependencyDomain> domains = curdRepository.findByPath(domain.getPath());
        for (DependencyDomain dependencyDomain : domains) {
            if (dependencyDomain.equals(domain)) {
                return dependencyDomain.getId().toString();
            }
        }

        long nextId = curdRepository.getMaxId() + 1;
        domain.setId(nextId);
        curdRepository.save(domain);
        return "" + nextId;
    }

    public List<DependencyDomain> list() {
        return null;
    }

    public void update(DependencyDomain domain) {
        curdRepository.save(domain);
    }

    public DependencyDomain findDomainByPath(String path) {

        return null;
    }
}
