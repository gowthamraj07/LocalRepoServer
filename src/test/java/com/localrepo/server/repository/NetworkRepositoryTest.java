package com.localrepo.server.repository;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.Assert.*;

public class NetworkRepositoryTest {

    @Test
    public void shouldReturnLastPartIfItHasSlash() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new NetworkRepository(callback);

        String fileName = null;
        try {
            fileName = repository.getFileName(new URL("http://localhost:8081/test/test.html"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        assertEquals("test.html", fileName);
    }

    @Test
    public void shouldLogErrorWhenUnableToDownloadFileFromRequestedURL() {
        NetworkRepository.Callback callback = Mockito.mock(NetworkRepository.Callback.class);
        NetworkRepository repository = new FailureNetworkRepository(callback);

        repository.downloadDependency("http://localhost:8081/test/test.html", "any local directory path");

        Mockito.verify(callback).onError("http://localhost:8081/test/test.html", "error message");
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