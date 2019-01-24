package com.localrepo.server.callback;

import com.localrepo.server.repository.DependencyRepository;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

public class NetworkCallbackTest {

    private static final String ANY_PATH = "any path";

    @Test
    public void shouldCallFindDomainByNameOfRepositoryClass() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        NetworkCallback callback = new NetworkCallback(repository);

        callback.getDomain(ANY_PATH);

        Mockito.verify(repository).findDomainByPath(ANY_PATH);
    }
}