package com.localrepo.server.repository;

import com.localrepo.server.domain.Repositories;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class NetworkRepositoryTest {

    private static final String REQUESTED_URL_PATH = "/test/test.html";
    private static final String FAKE_HTTPS_REPOSITORY_URL_1 = "http://localhost:8080/";
    private static final String FAKE_HTTPS_REPOSITORY_URL_2 = "http://test:8080/";
    private static final String FAKE_HTTPS_REPOSITORY_URL_3 = "http://test/";
    private URL source;
    private List<String> hostUrls;
    private Repositories repositories = new Repositories();

    @Before
    public void setUp() throws Exception {
        source = new URL(FAKE_HTTPS_REPOSITORY_URL_1 + REQUESTED_URL_PATH);
        hostUrls = Arrays.asList(FAKE_HTTPS_REPOSITORY_URL_1, FAKE_HTTPS_REPOSITORY_URL_2, FAKE_HTTPS_REPOSITORY_URL_3);
        repositories.setRepos(hostUrls);
    }

    @Test
    public void shouldReturnLastPartIfItHasSlash() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new NetworkRepository(callback, repositories);

        String fileName = repository.getFileName(source);

        assertEquals("test.html", fileName);
    }

    @Test
    public void shouldLogErrorWhenUnableToDownloadFileFromRequestedURL() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new FailureNetworkRepository(callback);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback).onError(REQUESTED_URL_PATH, "Unable to download dependency");
    }

    @Test
    public void shouldCallOnSuccessWhenAbleToDownloadFileFromRequestedUrl() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new SuccessNetworkRepository(callback, hostUrls);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback, times(1)).onSuccess(REQUESTED_URL_PATH, FAKE_HTTPS_REPOSITORY_URL_1);
    }

    @Test
    public void shouldPassThroughAllHostUrlWhenNetworkThrowsError() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        SpyRepository spyRepository = mock(SpyRepository.class);
        NetworkRepository repository = new SpyFailureNetworkRepository(callback, hostUrls, spyRepository);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        verify(spyRepository).download(FAKE_HTTPS_REPOSITORY_URL_1);
        verify(spyRepository).download(FAKE_HTTPS_REPOSITORY_URL_2);
        verify(spyRepository).download(FAKE_HTTPS_REPOSITORY_URL_3);
    }

    private class FailureNetworkRepository extends NetworkRepository {

        FailureNetworkRepository(Callback callback) {
            super(callback, repositories);
        }

        @Override
        void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {
            throw new IOException("error message");
        }

    }

    private class SuccessNetworkRepository extends NetworkRepository {
        SuccessNetworkRepository(Callback callback, List<String> hostUrls) {
            super(callback, repositories);
        }

        @Override
        void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {

        }

    }

    private class SpyFailureNetworkRepository extends NetworkRepository {
        private final SpyRepository spyRepository;

        SpyFailureNetworkRepository(Callback callback, List<String> hostUrls, SpyRepository spyRepository) {
            super(callback, repositories);
            this.spyRepository = spyRepository;
        }

        @Override
        void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {
            spyRepository.download(host);
            throw new IOException("error message");
        }
    }

    private interface SpyRepository {
        void download(String repositoryUrl);
    }
}