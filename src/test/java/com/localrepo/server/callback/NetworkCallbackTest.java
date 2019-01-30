package com.localrepo.server.callback;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class NetworkCallbackTest {

    private static final String ANY_PATH = "any path";
    public static final String ANY_HOST = "host";

    @Test
    public void shouldCallFindDomainByNameOfRepositoryClass() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        NetworkCallback callback = new NetworkCallback(repository);

        callback.getDomain(ANY_PATH);

        Mockito.verify(repository).findDomainByPath(ANY_PATH);
    }

    @Test
    public void shouldUpdateRepositoryWithHostDetailsOnSuccess() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        DependencyDomain domain = new DependencyDomain();
        Mockito.when(repository.findDomainByPath(ANY_PATH)).thenReturn(domain);
        NetworkCallback callback = new NetworkCallback(repository);

        callback.onSuccess(ANY_PATH, ANY_HOST);

        Mockito.verify(repository).findDomainByPath(ANY_PATH);
        Mockito.verify(repository).update(domain);
        Assert.assertEquals(ANY_HOST, domain.getHost());
    }
}