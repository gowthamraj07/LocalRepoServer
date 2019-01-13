package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DependencyCrudRepository extends CrudRepository<DependencyDomain, Long> {
}
