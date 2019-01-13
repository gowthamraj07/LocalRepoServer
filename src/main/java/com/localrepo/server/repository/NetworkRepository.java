package com.localrepo.server.repository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class NetworkRepository {
    public void downloadDependency(String path, String localDirectoryPath) {
        try {
            URL source = new URL(path);
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
