package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.PrintWriter;

public class URLControllerTest {

    @Test
    public void shouldPrintTheRequestURL() {
        PrintWriter writer = Mockito.mock(PrintWriter.class);
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        URLController controller = new URLController(writer, repository);

        String path = "any path";
        controller.getDependency(path);

        Mockito.verify(writer).println(path);
    }

    @Test
    public void shouldMakeRepositoryCallToFetchId() {
        DependencyRepository repository = Mockito.mock(DependencyRepository.class);
        PrintWriter writer = Mockito.mock(PrintWriter.class);
        URLController controller = new URLController(writer, repository);

        String path = "any path";
        controller.getDependency(path);

        Mockito.verify(repository).getId(ArgumentMatchers.any(DependencyDomain.class));
    }
}