package com.localrepo.server.callback;

import com.localrepo.server.domain.DependencyDomain;
import com.localrepo.server.repository.DependencyRepository;
import com.localrepo.server.repository.FileRepository;
import com.localrepo.server.repository.NetworkRepository;

public class NetworkCallback implements NetworkRepository.Callback {
    private DependencyRepository repository;
    private FileRepository fileRepository;

    public NetworkCallback(DependencyRepository repository, FileRepository fileRepository) {
        this.repository = repository;
        this.fileRepository = fileRepository;
    }

    @Override
    public void onError(String url, String message) {
    	if(getDomain(url) == null) {
    		System.out.println(">>>>>>>>"+url);
    		return;
    	}

    	if(getDomain(url).getId() == null) {
    		System.out.println(">>>>>>>> (id is null) :"+url);
    		return;
    	}

        String folderName = getDomain(url).getId().toString();
        fileRepository.deleteDirectory(folderName);
        repository.delete(getDomain(url));
    }

    @Override
    public void onSuccess(String path, String host) {
        DependencyDomain domain = getDomain(path);
        domain.setHost(host);
        repository.update(domain);
    }

    protected DependencyDomain getDomain(String urlPath) {
        return repository.findDomainByPath(urlPath);
    }
}
