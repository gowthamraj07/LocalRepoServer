package com.localrepo.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.localrepo.server.callback.NetworkCallback;
import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.domain.Repositories;
import com.localrepo.server.repository.DependencyCrudRepository;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Controller
public class URLController {

    private static final Logger log = LoggerFactory.getLogger(URLController.class);

    public static final byte[] EMPTY_BYTES = new byte[0];
    private final DependencyRepository repository;
    private final FileRepository fileRepository;
    private final NetworkRepository networkRepository;

    @Autowired
    public URLController(DependencyCrudRepository curdRepository, Repositories repositories) {
        this.repository = new DependencyRepository(curdRepository);
        this.fileRepository = new FileRepository();
        this.networkRepository = new NetworkRepository(new NetworkCallback(this.repository, fileRepository), repositories);
    }

    URLController(DependencyRepository repository, FileRepository fileRepository, NetworkRepository networkRepository) {
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.networkRepository = networkRepository;
    }

    @RequestMapping(path = "/cache/**")
    @ResponseBody
    public byte[] getDependency(HttpServletRequest request) {
        return getDependency(request.getRequestURI().substring(request.getContextPath().length() + 6));
    }


    byte[] getDependency(String path) {
        log.debug("Requested {}", path);
        DependencyDomain domain = new DependencyDomain();
        domain.setRequestedPath(path);

        String id = repository.getId(domain);
        String localFilePath = fileRepository.getRepoDirectoryPath() + id;
        if (!fileRepository.isDirectoryExists(localFilePath)) {
            fileRepository.createDirectory(localFilePath);
            networkRepository.downloadDependency(path, localFilePath);
        }

        File directoryPath = new File(localFilePath);
        File inputFile = null;
        File[] files = directoryPath.listFiles();

        if(files == null) {
            return EMPTY_BYTES;
        }

        for (File file : files) {
            if (file.getName().equals(".") || file.getName().equals("..")) {
                continue;
            }

            inputFile = file;
            break;
        }

        if (inputFile == null) {
            return EMPTY_BYTES;
        }

        log.debug("Serving {} from cache", inputFile.getPath());
        try {
            return IOUtils.toByteArray(Files.newInputStream(inputFile.toPath()));
        } catch (IOException e) {
            log.warn("Could not read cached file {}", inputFile.getPath(), e);
        }

        return EMPTY_BYTES;
    }

    @RequestMapping(path = "/list")
    @ResponseBody
    public List<DependencyDomain> listAvailableDependencies() {
        return repository.list();
    }

    @RequestMapping(path = "/delete")
    @ResponseBody
    public String deleteWithNull() {
        repository.deleteWithNull();
        return "{\"ok\"}";
    }

}
