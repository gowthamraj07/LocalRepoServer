package com.localrepo.server.repository;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import static org.junit.Assert.assertEquals;

public class NetworkRepositoryTest {

    private static final String REQUESTED_URL_PATH = "http://localhost:8081/test/test.html";
    private URL source;

    @Before
    public void setUp() throws Exception {
        source = new URL(REQUESTED_URL_PATH);
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

    private class FailureNetworkRepository extends NetworkRepository {

        FailureNetworkRepository(Callback callback) {
            super(callback);
        }

        @Override
        void downloadFile(URL source, File file) throws IOException {
            throw new IOException("error message");
        }
    }
}