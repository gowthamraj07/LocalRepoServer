package com.localrepo.server;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;

public class URLControllerTest {

    @Test
    public void shouldPrintTheRequestURL() {
        PrintWriter writer = Mockito.mock(PrintWriter.class);
        URLController controller = new URLController(writer);

        String path = "any path";
        controller.getDependency(path);

        Mockito.verify(writer).println(path);
    }
}