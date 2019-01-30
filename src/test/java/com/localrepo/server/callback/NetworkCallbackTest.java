package com.localrepo.server.callback;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class NetworkCallbackTest {

    private static final String ANY_PATH = "any path";
    private static final String ANY_HOST = "host";
    private static final String ANY_ERROR_MESSAGE = "ANY ERROR MESSAGE";
    private FileRepository fileRepository;

    @Before
    public void setUp() {
        fileRepository = Mockito.mock(FileRepository.class);
    }

    @Test
    public void shouldCallFindDomainByNameOfRepositoryClass() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        NetworkCallback callback = new NetworkCallback(repository, fileRepository);

        callback.getDomain(ANY_PATH);

        Mockito.verify(repository).findDomainByPath(ANY_PATH);
    }

    @Test
    public void shouldUpdateRepositoryWithHostDetailsOnSuccess() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        DependencyDomain domain = new DependencyDomain();
        Mockito.when(repository.findDomainByPath(ANY_PATH)).thenReturn(domain);
        NetworkCallback callback = new NetworkCallback(repository, fileRepository);

        callback.onSuccess(ANY_PATH, ANY_HOST);

        Mockito.verify(repository).findDomainByPath(ANY_PATH);
        Mockito.verify(repository).update(domain);
        Assert.assertEquals(ANY_HOST, domain.getHost());
    }

    @Test
    public void shouldDeleteTheFolderOnError() {
        Long dbEntryId = 1L;
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        DependencyDomain domain = new DependencyDomain();
        domain.setId(dbEntryId);
        Mockito.when(repository.findDomainByPath(ANY_PATH)).thenReturn(domain);
        NetworkCallback callback = new NetworkCallback(repository, fileRepository);

        callback.onError(ANY_PATH, ANY_ERROR_MESSAGE);

        String folderName = dbEntryId.toString();
        Mockito.verify(fileRepository).deleteDirectory(folderName);
    }
}