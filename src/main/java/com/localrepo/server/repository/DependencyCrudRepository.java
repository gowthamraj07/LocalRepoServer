package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;
import org.springframework.data.repository.CrudRepository;

public interface DependencyCrudRepository extends CrudRepository<DependencyDomain, Long> {
}
