package com.localrepo.server;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyCrudRepository;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.*;

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
        this.networkRepository = new NetworkRepository();
    }

    public URLController(PrintWriter writer, DependencyRepository repository, FileRepository fileRepository, NetworkRepository networkRepository) {
        this.writer = writer;
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.networkRepository = networkRepository;
    }

    @RequestMapping(path = "/cache/**")
    @ResponseBody
    public byte[] getDependency(HttpServletRequest request) {
        try {
            return getDependency(request.getRequestURI().substring(request.getContextPath().length() + 6));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return new byte[0];
    }


    public byte[] getDependency(String path) throws FileNotFoundException {
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
}
