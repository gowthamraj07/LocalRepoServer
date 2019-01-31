package com.localrepo.server.repository;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.times;

public class NetworkRepositoryTest {

    private static final String REQUESTED_URL_PATH = "/test/test.html";
    private static final String FAKE_HTTPS_REPOSITORY_URL_1 = "http://localhost:8080/";
    private static final String FAKE_HTTPS_REPOSITORY_URL_2 = "http://test:8080/";
    private static final String FAKE_HTTPS_REPOSITORY_URL_3 = "http://test/";
    private URL source;
    private List<String> hostUrls;

    @Before
    public void setUp() throws Exception {
        source = new URL(FAKE_HTTPS_REPOSITORY_URL_1 + REQUESTED_URL_PATH);
        hostUrls = Arrays.asList(FAKE_HTTPS_REPOSITORY_URL_1, FAKE_HTTPS_REPOSITORY_URL_2, FAKE_HTTPS_REPOSITORY_URL_3);
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

        Mockito.verify(callback, times(hostUrls.size())).onError(REQUESTED_URL_PATH, "error message");
    }

    @Test
    public void shouldCallOnSuccessWhenAbleToDownloadFileFromRequestedUrl() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new SuccessNetworkRepository(callback, hostUrls);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback, times(1)).onSuccess(REQUESTED_URL_PATH, FAKE_HTTPS_REPOSITORY_URL_1);
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
            return NetworkRepositoryTest.FAKE_HTTPS_REPOSITORY_URL_1;
        }
    }
}