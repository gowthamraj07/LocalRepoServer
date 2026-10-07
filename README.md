# LocalRepoServer

A caching proxy for Maven and Gradle repositories that runs on your own machine. Every artifact your builds download
goes through it once and is kept, so:

 * builds keep working when the network is slow, flaky or gone, and after `~/.gradle/caches` or `~/.m2` is wiped
 * big downloads are not repeated across projects, Gradle user homes or clean CI-like builds
 * a project's dependencies can be packed into one zip and carried to another machine

It works for plain Maven and Gradle projects as well as Android and Kotlin/Compose Multiplatform builds. Maven Central,
Google Maven, the Gradle Plugin Portal, JetBrains Compose and JitPack are set up out of the box, and your projects need
no changes.

```
 Gradle / Maven build ──▶ LocalRepoServer (localhost:8082) ──▶ Maven Central, Google, Plugin Portal, ...
                                  │
                                  └── ~/.localrepo/cache   (kept forever, served offline)
```

## Contents

 * [Getting started](#getting-started): install, start, check it works
 * [Use it with your projects](#use-it-with-your-projects): every project at once, or one project by hand
 * [Day-to-day use](#day-to-day-use): the web UI, offline mode, moving to another machine
 * [Configuration](#configuration): cache size, cache on an external disk, private repositories
 * [Troubleshooting](#troubleshooting), [API](#api), [Development](#development)

## Getting started

### 1. Requirements

 * macOS or Linux (on Windows, run it under WSL)
 * Java 21 or newer on the `PATH` or in `JAVA_HOME`. Check with `java -version`
 * `curl`

### 2. Install

```
curl -fsSL https://raw.githubusercontent.com/gowthamraj07/LocalRepoServer/master/install.sh | bash
```

This puts the server in `~/.localrepo/server.jar` and the `localrepo` command in `~/.local/bin`. If the installer says
`~/.local/bin` is not on your `PATH`, add it, for example in `~/.zshrc` or `~/.bashrc`:

```
export PATH="$HOME/.local/bin:$PATH"
```

To install from source instead: `./mvnw package -DskipTests && ./install.sh --jar target/server-<version>.jar`.

### 3. Start it

```
localrepo service install    # macOS: start now and at every login (recommended)
# or
localrepo start              # start now, in the background, until you stop it or log out
```

### 4. Check that it works

```
localrepo status
curl -I http://localhost:8082/cache/junit/junit/4.13.2/junit-4.13.2.pom     # HTTP/1.1 200
```

`localrepo status` should say `LocalRepoServer is running at http://127.0.0.1:8082`. Open the web UI with
`localrepo open` (http://localhost:8082): the file you just fetched is on the **Artifacts** page.

### 5. Point your builds at it

```
localrepo install-gradle     # every Gradle build on this machine now goes through the server
localrepo install-maven      # and every Maven build
```

Build any project as usual. The first build fills the cache (watch it on the **Downloads** page); after that, builds
are served from it. [Use it with your projects](#use-it-with-your-projects) explains what these two commands change,
and how to set up a single project instead.

## Use it with your projects

There are two ways. **Global setup** routes every build on the machine through the server without touching any project;
it is the easiest and the recommended way. **Per-project setup** changes one project's build files, for example to try
the server out or to share the setup with a team.

The server answers at:

| URL | what it serves |
|---|---|
| `http://localhost:8082/cache/` | every built-in upstream in order (Central, Google, Plugin Portal, ...). Use this one |
| `http://localhost:8082/repo/<name>/` | one upstream only, e.g. `/repo/google/` |

### Global setup: Gradle

```
localrepo install-gradle        # undo: localrepo uninstall-gradle
```

This writes an init script to `~/.gradle/init.d/localrepo.init.gradle` (or `$GRADLE_USER_HOME/init.d`). Gradle runs it
for every build, and it puts the server **in front of** plugin resolution and every repository list a build declares
(`pluginManagement`, `dependencyResolutionManagement`, `buildscript` and project `repositories`). The build's own
repositories stay behind it, so:

 * anything the server cannot serve (a private repository it does not know) still resolves as before
 * when the server is not running, builds use their own repositories as if nothing was installed
 * to skip the server for one build, use `./gradlew build -Plocalrepo.disabled=true` (or `LOCALREPO_DISABLED=1`)

It covers Kotlin and Groovy DSL builds, Android Gradle Plugin and Kotlin Multiplatform builds (tested with Gradle 9).
Android Studio and IntelliJ use the same init script when they sync.

### Global setup: Maven

```
localrepo install-maven         # undo: localrepo uninstall-maven
```

This adds a mirror for all repositories (`mirrorOf *`) at the top of `~/.m2/settings.xml`, creating the file if needed,
leaving the rest of it untouched and backing it up first:

```xml
<mirror>
  <id>localrepo</id>
  <name>LocalRepoServer</name>
  <mirrorOf>*</mirrorOf>
  <url>http://localhost:8082/cache</url>
</mirror>
```

Mirrors you declared for a specific repository id still win. Maven has no fallback: while the mirror is installed, the
server must be running (`localrepo service install` takes care of that).

To try it without touching your settings, download the generated settings file and pass it to one build:

```
curl -o localrepo-settings.xml http://localhost:8082/setup/maven/settings.xml
mvn -s localrepo-settings.xml package
```

### Per-project setup: Gradle (Kotlin DSL)

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("http://localhost:8082/cache")
            isAllowInsecureProtocol = true      // plain http is fine: the server only listens on localhost
        }
        gradlePluginPortal()                    // fallback when the server is not running
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("http://localhost:8082/cache")
            isAllowInsecureProtocol = true
        }
        mavenCentral()
    }
}
```

### Per-project setup: Gradle (Groovy DSL)

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { url = 'http://localhost:8082/cache'; allowInsecureProtocol = true }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = 'http://localhost:8082/cache'; allowInsecureProtocol = true }
        mavenCentral()
    }
}
```

Older builds that declare repositories in `build.gradle` (`allprojects { repositories { ... } }` or `buildscript`) take
the same `maven { ... }` line as the first entry there.

### Per-project setup: Android and Kotlin/Compose Multiplatform

Google Maven and JetBrains Compose are already upstreams of `/cache`, so the server is simply put first and the usual
repositories stay as fallbacks. The same `settings.gradle.kts` works for Android apps and Kotlin/Compose Multiplatform
projects (tested with Android Gradle Plugin 9.2 and Compose Multiplatform 1.12 on Gradle 9.4):

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("http://localhost:8082/cache"); isAllowInsecureProtocol = true }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("http://localhost:8082/cache"); isAllowInsecureProtocol = true }
        google()
        mavenCentral()
        // Private repositories: keep them here AND add them as upstreams (see Configuration) so they are cached too
        maven {
            url = uri("https://maven.pkg.github.com/owner/repo")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

### Per-project setup: Maven

To route one project through the server without changing `~/.m2/settings.xml`, give it its own settings file:

```
mkdir -p .mvn
curl -o .mvn/localrepo-settings.xml http://localhost:8082/setup/maven/settings.xml
echo "--settings=.mvn/localrepo-settings.xml" >> .mvn/maven.config   # one option per line
```

`mvn` and `./mvnw` now use the server for this project only. Alternatively, declare it in `pom.xml` (this does not
cover repositories that dependencies themselves declare, which the mirror does):

```xml
<repositories>
  <repository>
    <id>localrepo</id>
    <url>http://localhost:8082/cache</url>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>localrepo</id>
    <url>http://localhost:8082/cache</url>
  </pluginRepository>
</pluginRepositories>
```

Maven 3.8+ blocks plain-http repositories, but not ones on localhost, so no extra settings are needed.

## Day-to-day use

### No waiting on big downloads

A file the cache does not have is streamed to the build **while** it downloads, with its real size, so the build shows
progress straight away instead of waiting for the server. Several builds asking for the same file share one download,
and the download finishes and is cached even if the build that started it gives up. The **Downloads** page shows what
is being fetched, how fast, and how far along.

### The web UI

`localrepo open`, or http://localhost:8082:

 * **Dashboard**: hit rate, bytes served from the cache, disk use, where the cache is and how much space is left
 * **Downloads**: live progress of everything being fetched, and recent results
 * **Artifacts**: search the cache by path, see sizes, hits and last use, delete or re-fetch files
 * **Upstreams**: the repositories behind the server, their filters, and whether they are reachable; add your own
 * **Maintenance**: export and import bundles, prefetch, purge, size limit
 * **Setup**: install or remove the Gradle and Maven setup; check the whole cache for corruption

### Working offline

The switch in the UI header (or `localrepo offline on` / `off`) puts the server in **offline mode**: it never contacts
an upstream, serves what it has and answers 404 for the rest. Without the switch, it also keeps working when the
network is gone: everything already cached is served as usual.

To make sure a project builds offline, build it once online (or prefetch it, see below) before you go.

### Taking dependencies to another machine

```
localrepo export bundle.zip          # everything cached
localrepo export bundle.zip 1h       # only what was used in the last hour: build a project first, then export
localrepo import bundle.zip          # on the other machine
```

Bundles are zips of the cache with SHA-256 checksums. Importing keeps files the cache already has and rejects anything
whose bytes do not match. The cache directory is itself a plain Maven repository layout (`~/.localrepo/cache/<upstream>/`).

### Command reference

| command | |
|---|---|
| `localrepo start` / `stop` / `restart` / `status` | run the server in the background and check on it |
| `localrepo service install` / `uninstall` | macOS: start at every login (launchd) |
| `localrepo install-gradle` / `uninstall-gradle` | route every Gradle build through the server, or stop |
| `localrepo install-maven` / `uninstall-maven` | the same for Maven |
| `localrepo open` | the web UI |
| `localrepo offline on` / `off` | never contact an upstream |
| `localrepo export <file> [age]` / `import <file>` | bundles |
| `localrepo move-cache <dir>` | move the cache to another folder or disk and use it from now on |
| `localrepo logs` | follow `~/.localrepo/logs/server.log` |
| `localrepo version` | the installed version |

Environment: `LOCALREPO_HOME` (default `~/.localrepo`), `LOCALREPO_PORT` (default `8082`), `LOCALREPO_ARGS` (extra
server arguments), `JAVA_HOME`.

To uninstall completely: `localrepo uninstall-gradle; localrepo uninstall-maven; localrepo service uninstall;
localrepo stop`, then delete `~/.localrepo` and `~/.local/bin/localrepo`.

## Configuration

Put settings in `~/.localrepo/config.yml` and restart (`localrepo restart`). The defaults are in
[application.yml](src/main/resources/application.yml).

```yaml
localrepo:
  max-size: 20GB              # delete the least recently used files when the cache grows past this
  pinned: [ "androidx/**" ]   # ...but never these
  metadata-ttl: 24h           # how long maven-metadata.xml and -SNAPSHOT files are served before rechecking
  offline: false
```

### Keeping the cache on another disk

The cache only grows (unless you set `max-size`), so a bigger external disk is a good home for it. Move it with:

```
localrepo move-cache /Volumes/MyDisk/LocalRepoCache
```

This stops the server (or the login service), moves everything already cached, records the new folder in
`~/.localrepo/config.yml` (`localrepo.cache-dir: "/Volumes/MyDisk/LocalRepoCache"`) and starts the server again. Moving
to another disk copies the files, so a large cache takes a while. To move it back, run it again with
`~/.localrepo/cache`. You can also set `localrepo.cache-dir` yourself and move the folder by hand while the server is
stopped.

When that disk is not connected:

 * the server keeps running, but reports itself as down (`/actuator/health` answers 503, `localrepo status` and the
   dashboard warn about it). It never creates a new, empty cache somewhere else in the meantime
 * **Gradle** builds notice and use their own repositories, as if the server were not running
 * **Maven** builds fail, because the mirror sends everything to the server; connect the disk, or
   `localrepo uninstall-maven` while you work without it
 * when the disk is back, everything works again on its own; no restart needed. This also covers the login service
   starting before the disk is mounted

**macOS asks once for permission.** The first time the login service reads the external disk, macOS asks whether
"java" may access files on a removable volume, and the server waits until you answer. Click **Allow**. If you missed the
prompt (the server does not come up after `move-cache`), open System Settings → Privacy & Security → Files & Folders →
java and turn on **Removable Volumes**. If java is not listed there, add the `java` binary under **Full Disk Access**
instead; the `JAVA_HOME` in `~/Library/LaunchAgents/com.localrepo.server.plist` shows which one runs.

Any disk macOS can write to works. APFS or Mac OS Extended are best; on exFAT the cache works, but file permissions
cannot be set.

### Private repositories

A company Nexus or GitHub Packages should be an upstream too, or its artifacts are not cached and builds that need them
fail offline. Add it on the **Upstreams** page of the UI, or in `~/.localrepo/upstreams.yml`; extra upstreams are tried
after the built-in ones:

```yaml
localrepo:
  extra-upstreams:
    - name: github-mobile-deps
      url: https://maven.pkg.github.com/owner/repo
      includes: [ "io/github/owner/**" ]          # only ask it for these paths
      credentials: { gradle-property-username: gpr.user, gradle-property-password: gpr.key }
    - name: company-nexus
      url: https://nexus.example.com/repository/maven-releases
      credentials: { username-env: NEXUS_USER, password-env: NEXUS_PASSWORD }
```

Credentials are given by name, never stored: keys of `~/.gradle/gradle.properties` (`gradle-property-username`,
`-password`, `-token`), where Gradle users keep them already, or environment variables (`username-env`,
`password-env`, `token-env`).

### How requests are answered

 * A miss asks the upstreams in order, skipping those whose `includes`/`excludes` rule the path out. `/repo/<name>/`
   asks one upstream directly. Setting `localrepo.upstreams` replaces the built-in list entirely
 * Released artifacts never change, so once cached they are served forever. Version listings and snapshots are
   rechecked with a conditional request after `metadata-ttl`; if the upstream is down the cached copy is served
   (marked `X-LocalRepo-Stale: true`)
 * Every download is checked against the SHA-256 or SHA-1 its repository publishes before it is cached. A mismatch is
   quarantined in `~/.localrepo/cache/.quarantine` and never served
 * A path that no upstream has is answered with 404, without asking again for 5 minutes (`negative-cache-ttl`)
 * An upstream that fails (a broken connection, a 5xx or 429) is asked once more. If it still fails, the answer is
   502, not 404, so the build tries again next time instead of remembering a miss
 * Prefetch before going offline: `group:artifact:version` lines, repository paths or a Gradle
   `verification-metadata.xml`, on the Maintenance page or `POST /api/prefetch`

## Troubleshooting

 * **Is it running?** `localrepo status` shows whether the server runs, its hit rate and what is installed;
   `localrepo logs` follows the log in `~/.localrepo/logs/server.log`
 * **A Gradle build does not go through the server.** Is the server running (the init script quietly steps aside when
   it is not)? Was the build started with `-Plocalrepo.disabled=true`? Is the init script there
   (`localrepo status` → `gradle: installed`)? A Gradle daemon started before `install-gradle` picks it up on its next
   build; `./gradlew --stop` if in doubt
 * **Maven: "Could not transfer" or "Connection refused" to 127.0.0.1:8082.** The mirror is installed but the server
   is not running: `localrepo start`, or `localrepo uninstall-maven`
 * **Maven: "was not found in http://127.0.0.1:8082/cache during a previous attempt".** Maven remembered a miss in
   `~/.m2/repository/**/*.lastUpdated`. Run once with `mvn -U`, or delete those files
 * **A dependency from a private repository is missing offline.** Add that repository on the Upstreams page (see
   [Private repositories](#private-repositories)), so the server caches it too
 * **The server does not come up after moving the cache to an external disk.** macOS is waiting for you to allow access
   to removable volumes; see [Keeping the cache on another disk](#keeping-the-cache-on-another-disk)
 * **"The cache folder ... is unavailable".** The disk holding the cache is not connected (or the folder was moved
   or deleted). Connect the disk; the server picks it up within seconds. See
   [Keeping the cache on another disk](#keeping-the-cache-on-another-disk)
 * **Port 8082 is taken.** Use another: `LOCALREPO_PORT=8090 localrepo start`, then run `install-gradle` /
   `install-maven` again so they point at the new port
 * **Suspect a corrupt file.** Setup → Check the cache, or delete it on the Artifacts page; it is fetched again on next
   use

## API

Changes need the header `X-LocalRepo-Action: true`; a browser only sends a custom header from the server's own pages,
so other websites cannot trigger them.

| | |
|---|---|
| `GET /cache/<path>`, `/group/<path>` | an artifact from the cache or the upstreams; `/repo/<name>/<path>` for one upstream |
| `GET /api/artifacts?q=&repository=&page=&size=` | cached files with coordinates, size, hits and last use |
| `DELETE /api/artifacts?repository=&path=` | delete a file or everything below a path |
| `POST /api/artifacts/refetch?repository=&path=` | download one file again |
| `GET /api/stats`, `GET /api/downloads` | usage totals, cache folder and free space; active and recent downloads |
| `GET /api/events` | server-sent events: `download-started`, `-progress`, `-completed`, `-failed`, `cache-hit` |
| `GET/POST /api/offline` | offline mode, `{"enabled": true}` |
| `GET /api/export?repository=&path=&usedWithin=`, `POST /api/import` | bundles |
| `POST /api/prefetch`, `POST /api/purge?path=&unusedFor=`, `POST /api/evict`, `POST /api/verify` | maintenance |
| `GET /setup/gradle`, `POST /setup/gradle/install`, `/uninstall` | the Gradle init script; same under `/setup/maven` |
| `GET /setup/maven/settings.xml` | a Maven settings file with the mirror, for `mvn -s` |
| `GET /actuator/health` | 200 when the server can cache, 503 while the cache folder is unavailable |

## Development

```
./mvnw verify                                   # unit and integration tests, including real Gradle builds
./mvnw package -DskipTests && scripts/test-cli.sh
scripts/smoke.sh <gradle project> <tasks>       # proves a project builds offline from the server alone
scripts/smoke.sh --maven <maven project> <goals>
```

`./install.sh --jar target/server-<version>.jar` installs a local build. Pushing a `v<version>` tag that matches the
version in `pom.xml` publishes a release. See [docs/pilot-2026.md](docs/pilot-2026.md) for results on real projects.
