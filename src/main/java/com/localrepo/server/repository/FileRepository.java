package com.localrepo.server.repository;

import java.io.File;

public class FileRepository {
    public void createDirectory(String directoryId) {
        File file = new File(getRepoDirectoryPath() + directoryId);
        if (!file.exists()) {
            file.mkdir();
        }
    }

    public boolean isDirectoryExists(String path) {
        return new File(path).exists();
    }

    public String getRepoDirectoryPath() {
        return "./";
    }
}
