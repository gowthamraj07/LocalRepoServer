package com.localrepo.server.repository;

import java.io.File;

public class FileRepository {
    public void createDirectory(String directoryId) {
        File file = new File(directoryId);
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public boolean isDirectoryExists(String path) {
        return new File(path).exists();
    }

    public String getRepoDirectoryPath() {
        return "./local_repo/";
    }

    public void deleteDirectory(String folderName) {
        File folder = new File(getRepoDirectoryPath() + folderName);
        if(!folder.exists()) {
            return;
        }

        if(!folder.isDirectory()) {
            return;
        }

        File[] files = folder.listFiles();

        if(files == null) {
            folder.delete();
            return;
        }

        for (File file : files) {
            file.delete();
        }

        folder.delete();
    }
}
