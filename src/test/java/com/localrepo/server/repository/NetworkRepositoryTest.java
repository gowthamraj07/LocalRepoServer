package com.localrepo.server.repository;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import static com.localrepo.server.repository.NetworkRepository.HTTPS_REPOSITORY_URL;
import static org.junit.Assert.assertEquals;

public class NetworkRepositoryTest {

    private static final String REQUESTED_URL_PATH = "/test/test.html";
    private URL source;

    @Before
    public void setUp() throws Exception {
        source = new URL(HTTPS_REPOSITORY_URL + REQUESTED_URL_PATH);
    }

    @Test
    public void shouldReturnLastPartIfItHasSlash() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new NetworkRepository(callback);

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
        NetworkRepository repository = new SuccessNetworkRepository(callback);

        repository.downloadDependency(REQUESTED_URL_PATH, "any local directory path");

        Mockito.verify(callback).onSuccess( REQUESTED_URL_PATH);
    }

    private class FailureNetworkRepository extends NetworkRepository {

        FailureNetworkRepository(Callback callback) {
            super(callback);
        }

        @Override
        void downloadFile(URL source, File file) throws IOException {
            throw new IOException("error message");
        }
    }

    private class SuccessNetworkRepository extends NetworkRepository {
        SuccessNetworkRepository(Callback callback) {
            super(callback);
        }

        @Override
        void downloadFile(URL source, File file) throws IOException {

        }
    }
}