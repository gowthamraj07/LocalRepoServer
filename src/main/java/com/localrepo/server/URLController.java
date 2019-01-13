package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.springframework.stereotype.Controller;

import java.io.PrintWriter;

@Controller
public class URLController {


    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;
    private NetworkRepository networkRepository;

    public URLController(PrintWriter writer, DependencyRepository repository, FileRepository fileRepository, NetworkRepository networkRepository) {
        this.writer = writer;
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.networkRepository = networkRepository;
    }

    public void getDependency(String path) {
        writer.println(path);
        DependencyDomain domain = new DependencyDomain();
        domain.setRequestedPath(path);

        String id = repository.getId(domain);
        if(!fileRepository.isDirectoryExists(id)) {
            fileRepository.createDirectory(id);
        }

        networkRepository.downloadDependency(path);
    }
}
