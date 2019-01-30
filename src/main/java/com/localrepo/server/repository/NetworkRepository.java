package com.localrepo.server.repository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class NetworkRepository {


    static final String HTTPS_REPOSITORY_URL = "https://maven.google.com";
    private Callback callback;

    public NetworkRepository(Callback callback) {
        this.callback = callback;
    }

    public void downloadDependency(String path, String localDirectoryPath) {
        try {
            downloadDependencyFrom(path, localDirectoryPath, getHttpsRepositoryUrl());
            System.out.println("File created");
            callback.onSuccess(path, getHttpsRepositoryUrl());
        } catch (IOException e) {
            //e.printStackTrace();
            callback.onError(path, e.getMessage());
        }
    }

    String getHttpsRepositoryUrl() {
        return HTTPS_REPOSITORY_URL;
    }

    void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {
        String spec = host + path;
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
    }

    String getFileName(URL source) {
        String[] split = source.getPath().split("\\/");
        return split[split.length - 1];
    }

    public interface Callback {
        void onError(String url, String message);

        void onSuccess(String path, String host);
    }
}
