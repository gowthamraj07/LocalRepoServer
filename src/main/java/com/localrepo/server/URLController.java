package com.localrepo.server;

import com.localrepo.server.callback.NetworkCallback;
import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyCrudRepository;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.Collections;
import java.util.List;

@Controller
public class URLController {

    private DependencyRepository repository;
    private PrintWriter writer;
    private FileRepository fileRepository;
    private NetworkRepository networkRepository;

    @Autowired
    public URLController(DependencyCrudRepository curdRepository) {
        this.writer = new PrintWriter(System.out);
        this.repository = new DependencyRepository(curdRepository);
        this.fileRepository = new FileRepository();
        List<String> hostUrls = Collections.singletonList(NetworkRepository.HTTPS_REPOSITORY_URL);
        this.networkRepository = new NetworkRepository(new NetworkCallback(this.repository, fileRepository), hostUrls);
    }

    URLController(PrintWriter writer, DependencyRepository repository, FileRepository fileRepository, NetworkRepository networkRepository) {
        this.writer = writer;
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
        writer.println(path);
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
            return new byte[0];
        }

        for (File file : files) {
            if (file.getName().equals(".") || file.getName().equals("..")) {
                continue;
            }

            inputFile = file;
            break;
        }

        if (inputFile == null) {
            return new byte[0];
        }

        System.out.println("File to download : "+inputFile.getPath());
        try {
            return IOUtils.toByteArray(new FileInputStream(inputFile));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new byte[0];
    }

    @RequestMapping(path = "/list")
    @ResponseBody
    public List<DependencyDomain> listAvailableDependencies() {
        return repository.list();
    }

}
