package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DependencyCrudRepository extends CrudRepository<DependencyDomain, Long> {
    @Override
    List<DependencyDomain> findAll();

    List<DependencyDomain> findByPath(String path);

    @Query("SELECT coalesce(max(ch.id), 0) FROM DependencyDomain ch")
    Long getMaxId();
}
