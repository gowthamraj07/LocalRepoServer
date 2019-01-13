package com.localrepo.server.repository;

import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.Assert.*;

public class NetworkRepositoryTest {

    @Test
    public void shouldReturnLastPartIfItHasSlash() {
        NetworkRepository repository = new NetworkRepository();

        String fileName = null;
        try {
            fileName = repository.getFileName(new URL("http://localhost:8081/test/test.html"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        assertEquals("test.html", fileName);
    }
}