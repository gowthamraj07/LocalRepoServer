package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyCrudRepository;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

public class URLControllerTest {

    private static final String ANY_PATH = "any path";
    private static final String DIRECTORY_ID = "123";
    public static final String PREFIX = "./";

    private final DependencyCrudRepository curdRepository = Mockito.mock(DependencyCrudRepository.class);

    private URLController controller;
    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;
    private NetworkRepository networkRepository;

    @Before
    public void setUp() {
        File file = new File("./123");
        if (!file.exists()) {
            file.mkdir();
            File newFile = new File("./123/test.jar");
            try {
                newFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        repository = Mockito.mock(DependencyRepository.class);
        writer = Mockito.mock(PrintWriter.class);
        fileRepository = Mockito.mock(FileRepository.class);
        networkRepository = Mockito.mock(NetworkRepository.class);

        Mockito.when(fileRepository.getRepoDirectoryPath()).thenReturn("./");
        Mockito.when(repository.getId(ArgumentMatchers.any(DependencyDomain.class))).thenReturn(DIRECTORY_ID);

        controller = new URLController(writer, repository, fileRepository, networkRepository);
    }

    @Test
    public void shouldPrintTheRequestURL() {
        testControllerMethod();

        Mockito.verify(writer).println(ANY_PATH);
    }

    @Test
    public void shouldMakeRepositoryCallToFetchId() {
        testControllerMethod();

        Mockito.verify(repository).getId(ArgumentMatchers.any(DependencyDomain.class));
    }

    @Test
    public void shouldCreateFolderIfRepositoryReturnsIdAndWhenFolderDoesnotExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(false);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        testControllerMethod();

        Mockito.verify(fileRepository).isDirectoryExists(PREFIX +DIRECTORY_ID);
        Mockito.verify(fileRepository).createDirectory(PREFIX +DIRECTORY_ID);
    }

    @Test
    public void shouldNotCreateFolderWhenFolderAlreadyExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(true);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        testControllerMethod();

        Mockito.verify(fileRepository).isDirectoryExists(PREFIX +DIRECTORY_ID);
        Mockito.verify(fileRepository, Mockito.times(0)).createDirectory(DIRECTORY_ID);
    }

    @Test
    public void shouldMakeNetworkCallWhenDirectoryNotExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(false);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        testControllerMethod();

        String localDirectoryPath = fileRepository.getRepoDirectoryPath() + DIRECTORY_ID;
        Mockito.verify(networkRepository).downloadDependency(ANY_PATH, localDirectoryPath);
    }

    @Test
    public void shouldNotMakeNetworkCallIfDataAlreadyExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(PREFIX +DIRECTORY_ID)).thenReturn(true);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        testControllerMethod();

        Mockito.verifyZeroInteractions(networkRepository);
    }

    @After
    public void tearDown() {
        File newFile = new File("./123/test.jar");
        if(newFile.exists()) {
            newFile.delete();
        }
        File directory = new File("./123");
        if(directory.exists()) {
            directory.delete();
        }
    }

    private void testControllerMethod() {
        controller.getDependency(ANY_PATH);
    }

    private class FakeFailureRepository extends DependencyRepository {

        FakeFailureRepository() {
            super(curdRepository);
        }

        @Override
        public String getId(DependencyDomain domain) {
            return DIRECTORY_ID;
        }
    }
}