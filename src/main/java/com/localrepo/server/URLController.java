package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import org.springframework.stereotype.Controller;

import java.io.PrintWriter;

@Controller
public class URLController {


    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;

    public URLController(PrintWriter writer, DependencyRepository repository, FileRepository fileRepository) {
        this.writer = writer;
        this.repository = repository;
        this.fileRepository = fileRepository;
    }

    public void getDependency(String path) {
        writer.println(path);
        DependencyDomain domain = new DependencyDomain();
        domain.setRequestedPath(path);
        String id = repository.getId(domain);
        fileRepository.createDirectory(id);
    }
}
