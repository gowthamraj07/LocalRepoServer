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

### How to send every Gradle build on this machine through the server
With the server running, install its Gradle init script once:
```
curl -X POST -H 'X-LocalRepo-Action: true' http://localhost:8082/setup/gradle/install
```
It is written to `~/.gradle/init.d/localrepo.init.gradle` (or `$GRADLE_USER_HOME/init.d`). From then on every build,
plugins included, asks the server first and keeps its own repositories behind it. No project needs editing.

 * When the server is not running, builds use their own repositories as if nothing was installed
 * Skip the server for one build: `./gradlew build -Plocalrepo.disabled=true` (or `LOCALREPO_DISABLED=1`)
 * Check: `curl http://localhost:8082/setup/gradle`; remove: `curl -X POST -H 'X-LocalRepo-Action: true' http://localhost:8082/setup/gradle/uninstall`

To configure a single project by hand instead, add the server in front of its repositories:
```
maven {
    url "http://localhost:8082/cache"
    allowInsecureProtocol = true
}
```
-----

### How to send every Maven build on this machine through the server
```
curl -X POST -H 'X-LocalRepo-Action: true' http://localhost:8082/setup/maven/install
```
This adds a `mirrorOf *` mirror to `~/.m2/settings.xml` (creating it if needed), first in the list so it wins over
wildcard mirrors; mirrors you declared for a specific repository id still take precedence. The rest of the file is left
untouched and a backup is written next to it before every change.

 * Maven has no fallback: while the mirror is installed the server must be running. Uninstall with
   `curl -X POST -H 'X-LocalRepo-Action: true' http://localhost:8082/setup/maven/uninstall`, which restores the file
 * To try it without touching your settings: `curl -o localrepo-settings.xml http://localhost:8082/setup/maven/settings.xml`
   and build with `mvn -s localrepo-settings.xml ...`
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
