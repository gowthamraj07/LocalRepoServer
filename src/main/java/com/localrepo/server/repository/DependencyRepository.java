package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;

public class DependencyRepository {
    private DependencyCrudRepository curdRepository;

    public DependencyRepository(DependencyCrudRepository curdRepository) {
        this.curdRepository = curdRepository;
    }

    public String getId(DependencyDomain domain) {
        if(curdRepository == null) {
            return "-1";
        }

        Iterable<DependencyDomain> domains = curdRepository.findAll();
        Long count = 0l;
        for (DependencyDomain dependencyDomain : domains) {
            if(dependencyDomain.equals(domain)) {
                return dependencyDomain.getId().toString();
            }
            count++;
        }

        domain.setId(count);
        curdRepository.save(domain);
        return ""+count;
    }
}
