package com.localrepo.server.domain;


import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class DependencyDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String path;
    private String host;

    public void setRequestedPath(String path) {
        this.path = path;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof DependencyDomain)) {
            return false;
        }

        return path.equals(((DependencyDomain)obj).path);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public String toString() {
        return "DependencyDomain{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", host='" + host + '\'' +
                '}';
    }
}
