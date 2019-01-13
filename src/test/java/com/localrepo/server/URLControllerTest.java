package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.PrintWriter;

public class URLControllerTest {

    private static final String ANY_PATH = "any path";
    private static final String DIRECTORY_ID = "123";

    private URLController controller;
    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;

    @Before
    public void setUp() {
        repository = Mockito.mock(DependencyRepository.class);
        writer = Mockito.mock(PrintWriter.class);
        fileRepository = Mockito.mock(FileRepository.class);

        controller = new URLController(writer, repository, fileRepository);
    }

    @Test
    public void shouldPrintTheRequestURL() {
        controller.getDependency(ANY_PATH);

        Mockito.verify(writer).println(ANY_PATH);
    }

    @Test
    public void shouldMakeRepositoryCallToFetchId() {
        controller.getDependency(ANY_PATH);

        Mockito.verify(repository).getId(ArgumentMatchers.any(DependencyDomain.class));
    }

    @Test
    public void shouldCreateFolderIfRepositoryReturnsIdAndWhenFolderDoesnotExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(ANY_PATH)).thenReturn(false);
        controller = new URLController(writer, repository, fileRepository);

        controller.getDependency(ANY_PATH);

        Mockito.verify(fileRepository).createDirectory(DIRECTORY_ID);
    }

    private class FakeFailureRepository extends DependencyRepository {
        @Override
        public String getId(DependencyDomain domain) {
            return DIRECTORY_ID;
        }
    }
}