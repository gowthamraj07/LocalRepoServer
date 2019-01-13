package com.localrepo.server.repository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class NetworkRepository {


    private static final String HTTPS_REPOSITORY_URL = "https://jcenter.bintray.com/";

    public void downloadDependency(String path, String localDirectoryPath) {
        try {
            String spec = HTTPS_REPOSITORY_URL + path;
            System.out.println("spec : " + spec);
            URL source = new URL(spec);
            System.out.println(source.toString());
            FileUtils.copyURLToFile(source, new File(localDirectoryPath + File.pathSeparator + getFileName(source)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getFileName(URL source) {
        String[] split = source.getPath().split("\\/");
        return split[split.length - 1];
    }
}
