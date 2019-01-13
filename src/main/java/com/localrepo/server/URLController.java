package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import org.springframework.stereotype.Controller;

import java.io.PrintWriter;

@Controller
public class URLController {


    private PrintWriter writer;
    private DependencyRepository repository;

    public URLController(PrintWriter writer, DependencyRepository repository) {
        this.writer = writer;
        this.repository = repository;
    }

    public void getDependency(String path) {
        writer.println(path);
        DependencyDomain domain = new DependencyDomain();
        domain.setRequestedPath(path);
        repository.getId(domain);
    }
}
