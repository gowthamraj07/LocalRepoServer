# LocalRepoServer

### About
This application acts as a local repository server for Gradle projects.
-----

### How to start the local repository server
Run the following command in Terminal to start the Server.
```
java -jar server-1.0.0.jar --repos=<repo1>,<repo2>,...
```

Without `--repos` the server uses Maven Central and Google Maven.

Example (to use Maven Crental repository):
```
java -jar server-1.0.0.jar --repos=https://repo1.maven.org/maven2
```

Example (to use Google Crental repository):
```
java -jar server-1.0.0.jar --repos=https://dl.google.com/android/maven2
```

Example (to use Maven Crental repository and Google Crental repository):
```
java -jar server-1.0.0.jar --repos=https://repo1.maven.org/maven2,https://dl.google.com/android/maven2
```
-----

### How to configure the Gradle project to point to local repository server
Use the following code inside repository block of build.gradle file
```
maven {
    url "http://localhost:8082/cache"
}
```
-----

### How to initialize the local repository
 * Close all the IDEs (Eclipse/STS/IntelliJ/Android Studio)
 * delete the `~/.gradle/caches` folder
 * Open the IDE
 * clean and build the project `gradle clean build`
-----

### How it works
 * The server is a caching proxy. A request for `/cache/<path>` is served from `~/.localrepo/cache/default/<path>` when present, otherwise it is fetched from the upstreams in order and stored there in the standard Maven layout
 * Files are only stored once completely downloaded; a `<file>.meta.json` sidecar records where and when each came from
 * A path that no upstream has returns `404`, and is remembered for 5 minutes (`--localrepo.negative-cache-ttl`)
 * Change the cache location with `--localrepo.cache-dir=/some/dir`
-----

### End points
 * `http://localhost:8082/` will display all the cached files
 * `http://localhost:8082/list` returns the cached files as JSON
-----

### How to ship the cache
The cache directory is a plain Maven repository; copy `~/.localrepo/cache` to another machine to reuse it.
-----
