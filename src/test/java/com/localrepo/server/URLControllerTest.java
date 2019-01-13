package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.PrintWriter;

public class URLControllerTest {

    private URLController controller;
    private PrintWriter writer;
    private DependencyRepository repository;

    @Before
    public void setUp() {
        repository = Mockito.mock(DependencyRepository.class);
        writer = Mockito.mock(PrintWriter.class);
        controller = new URLController(writer, repository);
    }

    @Test
    public void shouldPrintTheRequestURL() {
        String path = "any path";
        controller.getDependency(path);

        Mockito.verify(writer).println(path);
    }

    @Test
    public void shouldMakeRepositoryCallToFetchId() {
        String path = "any path";
        controller.getDependency(path);

        Mockito.verify(repository).getId(ArgumentMatchers.any(DependencyDomain.class));
    }

}