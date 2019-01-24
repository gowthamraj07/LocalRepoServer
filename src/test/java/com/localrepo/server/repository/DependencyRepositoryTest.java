package com.localrepo.server.repository;

import com.localrepo.server.domain.DependencyDomain;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

public class DependencyRepositoryTest {

    private static final String ANY_PATH = "http://localhost:8080/test/sample";

    @Test
    public void shouldCallFindByPathOfCurdRepository() {
        DependencyCrudRepository crudRepository = getMock();
        DependencyRepository repository = new DependencyRepository(crudRepository);

        repository.findDomainByPath(ANY_PATH);

        Mockito.verify(crudRepository).findByPath(ANY_PATH);
    }

    @Test
    public void shouldReturnNewDomainIfFindByParthReturnsEmptyList() {
        DependencyCrudRepository crudRepository = getMock();
        List<DependencyDomain> listOfDomains = Collections.emptyList();
        Mockito.when(crudRepository.findByPath(ANY_PATH)).thenReturn(listOfDomains);
        DependencyRepository repository = new DependencyRepository(crudRepository);

        DependencyDomain domainByPath = repository.findDomainByPath(ANY_PATH);

        Assert.assertEquals("/test/sample", domainByPath.getPath());
        Assert.assertEquals("http://localhost:8080", domainByPath.getHost());
    }

    private DependencyCrudRepository getMock() {
        return Mockito.mock(DependencyCrudRepository.class);
    }
}