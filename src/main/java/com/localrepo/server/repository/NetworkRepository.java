package com.localrepo.server.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.localrepo.server.domain.Repositories;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class NetworkRepository {

    private static final Logger log = LoggerFactory.getLogger(NetworkRepository.class);


    public static final String GOOGLE_MAVEN_REPOSITORY_URL = "https://maven.google.com";
    public static final String JCENTER_MAVEN_REPOSITORY_URL = "https://jcenter.bintray.com/";
    public static final String MAVEN2_REPOSITORY_URL = "http://repo1.maven.org/maven2";

    private Callback callback;
    private Repositories repositories;

    public NetworkRepository(Callback callback, Repositories repositories) {
        this.callback = callback;
        this.repositories = repositories;
    }

    public void downloadDependency(String path, String localDirectoryPath) {
        boolean isEligibleForDelete = true;
        for (String hostUrl : repositories.getRepos()) {
            try {
                downloadDependencyFrom(path, localDirectoryPath, hostUrl);
                log.info("Caching {} from {}", path, hostUrl);
                callback.onSuccess(path, hostUrl);
                isEligibleForDelete = false;
                break;
            } catch (IOException e) {
                log.debug("Could not fetch {} from {}", path, hostUrl, e);
            }
        }

        if(isEligibleForDelete) {
            callback.onError(path, "Unable to cache dependency ("+path+")");
        }
    }

    void downloadDependencyFrom(String path, String localDirectoryPath, String host) throws IOException {
        String spec = host + path;
        URL source = new URL(spec);
        File file = new File(localDirectoryPath + "/" + getFileName(source));
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        file.createNewFile();

        new Thread(() -> {
            try {
                FileUtils.copyURLToFile(source, file, 5000, 5000);
            } catch (IOException e) {
                log.warn("Download of {} failed", source, e);
            }
        }).start();
        //FileUtils.copyURLToFile(source, file, 5000, 5000);
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
