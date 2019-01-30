package com.localrepo.server.repository;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.times;

public class NetworkRepositoryTest {

    private static final String REQUESTED_URL_PATH = "/test/test.html";
    private static final String HTTPS_REPOSITORY_URL = "http://localhost:8080/";
    private URL source;
    private List<String> hostUrls;

    @Before
    public void setUp() throws Exception {
        source = new URL(HTTPS_REPOSITORY_URL + REQUESTED_URL_PATH);
        hostUrls = Collections.singletonList(HTTPS_REPOSITORY_URL);
    }

    @Test
    public void shouldReturnLastPartIfItHasSlash() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new NetworkRepository(callback, hostUrls);

        String fileName = repository.getFileName(source);

        assertEquals("test.html", fileName);
    }

    @Test
    public void shouldLogErrorWhenUnableToDownloadFileFromRequestedURL() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new FailureNetworkRepository(callback);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback).onError(REQUESTED_URL_PATH, "error message");
    }

    @Test
    public void shouldCallOnSuccessWhenAbleToDownloadFileFromRequestedUrl() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new SuccessNetworkRepository(callback, hostUrls);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback, times(1)).onSuccess(REQUESTED_URL_PATH, HTTPS_REPOSITORY_URL);
    }

    private class FailureNetworkRepository extends NetworkRepository {

        FailureNetworkRepository(Callback callback) {
            super(callback, hostUrls);
        }

        @Override
        void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {
            throw new IOException("error message");
        }

    }

    private class SuccessNetworkRepository extends NetworkRepository {
        SuccessNetworkRepository(Callback callback, List<String> hostUrls) {
            super(callback, hostUrls);
        }

        @Override
        void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {

        }

        @Override
        String getHttpsRepositoryUrl() {
            return NetworkRepositoryTest.HTTPS_REPOSITORY_URL;
        }
    }
}