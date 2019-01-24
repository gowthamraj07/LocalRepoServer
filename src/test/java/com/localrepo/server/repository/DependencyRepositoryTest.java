package com.localrepo.server.repository;

import org.junit.Test;
import org.mockito.Mockito;

public class DependencyRepositoryTest {

    private static final String ANY_PATH = "any path";

    @Test
    public void shouldCallFindByPathOfCurdRepository() {
        DependencyCrudRepository crudRepository = Mockito.mock(DependencyCrudRepository.class);
        DependencyRepository repository = new DependencyRepository(crudRepository);

        repository.findDomainByPath(ANY_PATH);

        Mockito.verify(crudRepository).findByPath(ANY_PATH);
    }
}