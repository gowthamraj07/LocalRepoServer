package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.domain.RequestURLDomain;
import com.localrepo.server.repository.DependencyCrudRepository;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.junit.*;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

public class URLControllerTest {

    private static final String ANY_PATH = "any path";
    private static final String DIRECTORY_ID = "temp";
    private static final String PREFIX = "./";

    private final DependencyCrudRepository curdRepository = Mockito.mock(DependencyCrudRepository.class);

    private URLController controller;
    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;
    private NetworkRepository networkRepository;

    @Before
    public void setUp() {
        File file = new File(PREFIX + DIRECTORY_ID);
        if (!file.exists()) {
            file.mkdir();
            File newFile = new File(PREFIX + DIRECTORY_ID + "/test.jar");
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
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(false);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        controller.getDependency(ANY_PATH);

        Mockito.verify(fileRepository).isDirectoryExists(PREFIX + DIRECTORY_ID);
        Mockito.verify(fileRepository).createDirectory(PREFIX + DIRECTORY_ID);
    }

    @Test
    public void shouldNotCreateFolderWhenFolderAlreadyExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(true);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        controller.getDependency(ANY_PATH);

        Mockito.verify(fileRepository).isDirectoryExists(PREFIX + DIRECTORY_ID);
        Mockito.verify(fileRepository, Mockito.times(0)).createDirectory(DIRECTORY_ID);
    }

    @Test
    public void shouldMakeNetworkCallWhenDirectoryNotExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(DIRECTORY_ID)).thenReturn(false);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        controller.getDependency(ANY_PATH);

        String localDirectoryPath = fileRepository.getRepoDirectoryPath() + DIRECTORY_ID;
        Mockito.verify(networkRepository).downloadDependency(ANY_PATH, localDirectoryPath);
    }

    @Test
    public void shouldNotMakeNetworkCallIfDataAlreadyExists() {
        repository = new FakeFailureRepository();
        Mockito.when(fileRepository.isDirectoryExists(PREFIX + DIRECTORY_ID)).thenReturn(true);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        controller.getDependency(ANY_PATH);

        Mockito.verifyZeroInteractions(networkRepository);
    }

    @Test
    public void shouldMakeRepositoryCallToListAvailableDependencies() {
        controller.listAvailableDependencies();

        Mockito.verify(repository).list();
    }

    @Test
    public void shouldListDependencies() {
        List<DependencyDomain> requestUrlDomainList = Collections.emptyList();
        repository = new FakeRepository(requestUrlDomainList);
        Mockito.when(fileRepository.isDirectoryExists(PREFIX + DIRECTORY_ID)).thenReturn(true);
        controller = new URLController(writer, repository, fileRepository, networkRepository);

        List<DependencyDomain> actualResponseDomainList = controller.listAvailableDependencies();

        Assert.assertEquals(requestUrlDomainList, actualResponseDomainList);
    }

    @AfterClass
    public static void tearDown() {
        File newFile = new File(PREFIX + DIRECTORY_ID + "/test.jar");
        if (newFile.exists()) {
            newFile.delete();
        }
        File directory = new File(PREFIX + DIRECTORY_ID);
        if (directory.exists()) {
            directory.delete();
        }
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

    private class FakeRepository extends DependencyRepository {
        private List<DependencyDomain> requestUrlDomainList;

        FakeRepository(List<DependencyDomain> requestUrlDomainList) {
            super(curdRepository);
            this.requestUrlDomainList = requestUrlDomainList;
        }

        @Override
        public List<DependencyDomain> list() {
            return requestUrlDomainList;
        }
    }
}