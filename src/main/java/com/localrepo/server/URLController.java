package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

@Controller
public class URLController {


    private PrintWriter writer;
    private DependencyRepository repository;
    private FileRepository fileRepository;
    private NetworkRepository networkRepository;

    public URLController() {
        this.writer = new PrintWriter(System.out);
        this.repository = new DependencyRepository();
        this.fileRepository = new FileRepository();
        this.networkRepository = new NetworkRepository();
    }

    public URLController(PrintWriter writer, DependencyRepository repository, FileRepository fileRepository, NetworkRepository networkRepository) {
        this.writer = writer;
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.networkRepository = networkRepository;
    }

    @RequestMapping(path = "/{path}")
    public ResponseEntity<InputStreamResource> getDependency(@PathVariable("path") String path) throws FileNotFoundException {
        writer.println(path);
        DependencyDomain domain = new DependencyDomain();
        domain.setRequestedPath(path);

        String id = repository.getId(domain);
        String localFilePath = fileRepository.getRepoDirectoryPath() + id;
        if (!fileRepository.isDirectoryExists(id)) {
            fileRepository.createDirectory(id);
            networkRepository.downloadDependency(path, localFilePath);
        }

        File directoryPath = new File(localFilePath);
        File inputFile = null;
        for (File file : directoryPath.listFiles()) {
            if (file.getName().equals(".") || file.getName().equals("..")) {
                continue;
            }

            inputFile = file;
            break;
        }

        if (inputFile == null) {
            return ResponseEntity.notFound().build();
        }

        InputStreamResource resource = new InputStreamResource(new FileInputStream(inputFile));

        return ResponseEntity.ok()
                .contentLength(directoryPath.length())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(resource);
    }
}
