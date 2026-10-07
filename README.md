# LocalRepoServer

A caching proxy for Maven and Gradle repositories that runs on your own machine. Every artifact your builds download
goes through it once and is kept, so:

 * builds keep working when the network is slow, flaky or gone, and after `~/.gradle/caches` or `~/.m2` is wiped
 * big downloads are not repeated across projects, Gradle user homes or CI-like clean builds
 * a project's dependencies can be packed into one zip and carried to another machine

It handles plain Maven and Gradle projects as well as Android and Kotlin/Compose Multiplatform builds (Google Maven,
the Gradle Plugin Portal, JetBrains Compose and JitPack are set up out of the box), and it needs no change to the
projects themselves.

## Quick start

Needs Java 21 or newer.

```
curl -fsSL https://raw.githubusercontent.com/gowthamraj07/LocalRepoServer/master/install.sh | bash

localrepo start              # or: localrepo service install   (macOS: start at every login)
localrepo install-gradle     # every Gradle build on this machine now goes through the server
localrepo install-maven      # and every Maven build
localrepo open               # the web UI at http://localhost:8082
```

That is all: build as usual. The first build fills the cache; after that, builds are served from it.

To undo: `localrepo uninstall-gradle`, `localrepo uninstall-maven`, `localrepo service uninstall`, `localrepo stop`.

## What the builds see

**Gradle.** `install-gradle` puts an init script in `~/.gradle/init.d` (or `$GRADLE_USER_HOME/init.d`). It places the
server in front of plugin resolution and of every repository list a build declares; the build's own repositories stay
behind it, so anything the server cannot serve (a private repository it does not know) still resolves as before.

 * When the server is not running, builds use their own repositories as if nothing was installed
 * Skip the server for one build with `-Plocalrepo.disabled=true` (or `LOCALREPO_DISABLED=1`)

**Maven.** `install-maven` adds a `mirrorOf *` mirror at the top of `~/.m2/settings.xml`, creating the file if needed,
leaving the rest untouched and backing it up first. Mirrors you declared for a specific repository id still win.
Maven has no fallback: while the mirror is installed, the server must be running. To try it without touching your
settings, download `http://localhost:8082/setup/maven/settings.xml` and build with `mvn -s`.

**One project by hand**, without the global setup:

```kotlin
repositories {
    maven { url = uri("http://localhost:8082/cache"); isAllowInsecureProtocol = true }
}
```

## No waiting on big downloads

A file the cache does not have is streamed to the build **while** it downloads, with its real size, so the build shows
progress straight away instead of waiting for the server. Several builds asking for the same file share one download,
and the download finishes and is cached even if the build that started it gives up. The Downloads page of the UI shows
what is being fetched, how fast, and how far along.

## The web UI

`localrepo open`, or http://localhost:8082:

 * **Dashboard**: hit rate, bytes served from the cache, disk use
 * **Downloads**: live progress of everything being fetched, and recent results
 * **Artifacts**: search the cache by path, see sizes, hits and last use, delete or re-fetch files
 * **Upstreams**: the repositories behind the server, their filters, and whether they are reachable
 * **Maintenance**: export and import bundles, prefetch, purge, size limit
 * **Setup**: install or remove the Gradle and Maven setup; check the whole cache for corruption

The switch in the header puts the server in **offline mode**: it never contacts an upstream, serves what it has and
answers 404 for the rest.

## Taking dependencies to another machine

```
localrepo export bundle.zip          # everything cached
localrepo export bundle.zip 1h       # only what was used in the last hour: build a project first, then export
localrepo import bundle.zip          # on the other machine
```

Bundles are zips of the cache with SHA-256 checksums. Importing keeps files the cache already has and rejects anything
whose bytes do not match. The cache directory is itself a plain Maven repository layout (`~/.localrepo/cache/<upstream>/`).

## Configuration

Put settings in `~/.localrepo/config.yml`; the server applies it on start. The defaults are in
[application.yml](src/main/resources/application.yml).

```yaml
localrepo:
  max-size: 20GB              # delete the least recently used files when the cache grows past this
  pinned: [ "androidx/**" ]   # ...but never these
  metadata-ttl: 24h           # how long maven-metadata.xml and -SNAPSHOT files are served before rechecking
  offline: false
```

**Private repositories** (a company Nexus, GitHub Packages) should be upstreams too, or their artifacts are not cached
and builds that need them fail offline. Add them on the **Upstreams** page of the UI, or in
`~/.localrepo/upstreams.yml`; they are tried after the built-in ones:

```yaml
localrepo:
  extra-upstreams:
    - name: github-mobile-deps
      url: https://maven.pkg.github.com/owner/repo
      includes: [ "io/github/owner/**" ]          # only ask it for these paths
      credentials: { gradle-property-username: gpr.user, gradle-property-password: gpr.key }
```

Credentials are given by name, never stored: keys of `~/.gradle/gradle.properties` (`gradle-property-username`,
`-password`, `-token`), where Gradle users keep them already, or environment variables (`username-env`,
`password-env`, `token-env`).

 * A miss asks the upstreams in order, skipping those whose `includes`/`excludes` rule the path out. `/repo/<name>/`
   asks one upstream directly. `localrepo.upstreams` replaces the built-in list entirely
 * Released artifacts never change, so once cached they are served forever. Version listings and snapshots are
   rechecked with a conditional request after `metadata-ttl`; if the upstream is down the cached copy is served
   (marked `X-LocalRepo-Stale: true`)
 * Every download is checked against the SHA-256 or SHA-1 its repository publishes before it is cached. A mismatch is
   quarantined in `~/.localrepo/cache/.quarantine` and never served
 * A path that no upstream has is answered with 404 without asking again for 5 minutes (`negative-cache-ttl`)
 * Prefetch ahead of going offline: `group:artifact:version` lines, repository paths or a Gradle
   `verification-metadata.xml`, on the Maintenance page or `POST /api/prefetch`

## API

Changes need the header `X-LocalRepo-Action: true`; a browser only sends a custom header from the server's own pages,
so other websites cannot trigger them.

| | |
|---|---|
| `GET /cache/<path>`, `/group/<path>` | an artifact from the cache or the upstreams; `/repo/<name>/<path>` for one upstream |
| `GET /api/artifacts?q=&repository=&page=&size=` | cached files with coordinates, size, hits and last use |
| `DELETE /api/artifacts?repository=&path=` | delete a file or everything below a path |
| `POST /api/artifacts/refetch?repository=&path=` | download one file again |
| `GET /api/stats`, `GET /api/downloads` | usage totals; active and recent downloads |
| `GET /api/events` | server-sent events: `download-started`, `-progress`, `-completed`, `-failed`, `cache-hit` |
| `GET/POST /api/offline` | offline mode, `{"enabled": true}` |
| `GET /api/export?repository=&path=&usedWithin=`, `POST /api/import` | bundles |
| `POST /api/prefetch`, `POST /api/purge?path=&unusedFor=`, `POST /api/evict`, `POST /api/verify` | maintenance |
| `GET /setup/gradle`, `POST /setup/gradle/install`, `/uninstall` | the Gradle init script; same under `/setup/maven` |
| `GET /actuator/health` | liveness |

## Troubleshooting

 * `localrepo status` shows whether the server runs, its hit rate, and what is installed; `localrepo logs` follows the
   log in `~/.localrepo/logs/server.log`
 * A Gradle build that should go through the server does not: is the server running (the init script quietly steps
   aside when it is not), and was the build started with `-Plocalrepo.disabled=true`?
 * A dependency from a private repository is missing offline: add that repository on the Upstreams page (see
   Configuration), so the server caches it too
 * Suspect a corrupt file: Setup → Check the cache, or delete it on the Artifacts page; it is fetched again on next use

## Development

```
./mvnw verify                                   # unit and integration tests, including real Gradle builds
./mvnw package -DskipTests && scripts/test-cli.sh
scripts/smoke.sh <gradle project> <tasks>       # proves a project builds offline from the server alone
scripts/smoke.sh --maven <maven project> <goals>
```

`./install.sh --jar target/server-<version>.jar` installs a local build. Pushing a `v<version>` tag publishes a release.
See [docs/pilot-2026.md](docs/pilot-2026.md) for results on real projects.
