package com.localrepo.server.repository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class NetworkRepository {


    private static final String HTTPS_REPOSITORY_URL = "https://maven.google.com";

    public void downloadDependency(String path, String localDirectoryPath) {
        try {
            String spec = HTTPS_REPOSITORY_URL + path;
            System.out.println("spec : " + spec);
            URL source = new URL(spec);
            System.out.println(source.toString());
            System.out.println("file to create : " + (localDirectoryPath + "/" + getFileName(source)));
            File file = new File(localDirectoryPath + "/" + getFileName(source));
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            file.createNewFile();
            FileUtils.copyURLToFile(source, file);
            System.out.println("File created");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getFileName(URL source) {
        String[] split = source.getPath().split("\\/");
        return split[split.length - 1];
    }
}
