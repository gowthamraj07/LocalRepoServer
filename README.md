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
 * Released artifacts never change and are served from the cache forever. `maven-metadata.xml` and `-SNAPSHOT` files
   are checked with their upstream again after 24 hours (`--localrepo.metadata-ttl`) with a conditional request; if the
   upstream cannot be reached the cached copy is served with an `X-LocalRepo-Stale: true` header
 * Every download is checked against the SHA-256 (or SHA-1) its repository publishes before it is cached; a mismatch
   is moved to `~/.localrepo/cache/.quarantine` and never served. Responses carry `X-Checksum-Sha256` / `X-Checksum-Sha1`
 * Re-check the whole cache with `curl -X POST -H 'X-LocalRepo-Action: true' http://localhost:8082/api/verify` and read
   the report with `curl http://localhost:8082/api/verify`; corrupt files are deleted and fetched again on next use
 * Offline mode never contacts an upstream: start with `--localrepo.offline=true`, or switch at runtime with
   `curl -X POST -H 'X-LocalRepo-Action: true' -H 'Content-Type: application/json' -d '{"enabled":true}' http://localhost:8082/api/offline`
-----

### End points
 * `http://localhost:8082/`: the web UI (dashboard, live downloads, artifact browser, upstreams, setup)
 * `GET /api/artifacts?q=&repository=&page=&size=`: cached files with Maven coordinates, size, hits and last access
 * `DELETE /api/artifacts?repository=&path=`: delete a file or everything below a path (needs `X-LocalRepo-Action`)
 * `POST /api/artifacts/refetch?repository=&path=`: download one file again (needs `X-LocalRepo-Action`)
 * `GET /api/stats`: hits, misses, hit rate, bytes served from the cache and downloaded, disk use
 * `GET /api/downloads`: active and recent downloads with progress
 * `GET /api/events`: server-sent events (`download-started`, `download-progress`, `download-completed`,
   `download-failed`, `cache-hit`)
 * `GET /actuator/health`: liveness, used by the Gradle init script
-----

### How to ship the cache
Bundles carry cached files to another machine, with checksums (also on the Maintenance page of the UI):
```
# everything, or what one project used: build it first, then export what was used in that time
curl -o bundle.zip 'http://localhost:8082/api/export'
curl -o bundle.zip 'http://localhost:8082/api/export?usedWithin=1h'

# on the other machine
curl -X POST -H 'X-LocalRepo-Action: true' -H 'Content-Type: application/zip' --data-binary @bundle.zip \
  http://localhost:8082/api/import
```
Importing keeps files the cache already has and rejects anything that does not match the bundle's checksums.

### Keeping the cache in shape
 * `--localrepo.max-size=20GB` deletes the least recently used files when the cache grows past it (checked every
   10 minutes); `--localrepo.pinned=androidx/**,...` protects paths from that
 * `POST /api/purge?path=com/example&unusedFor=30d` deletes files below a path and/or unused for a while
 * `POST /api/prefetch` with `group:artifact:version` lines, repository paths, or a Gradle `verification-metadata.xml`
   downloads them ahead of time, e.g. before going offline; `GET /api/prefetch` shows progress
-----
