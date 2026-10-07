# Pilot, 2026-10-07

Two real projects, run with `scripts/smoke.sh` on an M-series MacBook Pro, Gradle and Maven from each project's wrapper.
Each run starts with **empty build-tool caches** (throwaway `GRADLE_USER_HOME` / Maven local repository) and goes
through a private LocalRepoServer. The offline phase then restarts the server with **every upstream unreachable**,
empties the build-tool caches again and cuts the build tool off from the internet (Gradle: dead system proxy except
localhost; Maven: `mirrorOf *` means it only talks to the server). Nothing in the projects was changed.

| Project | Build | Cold (empty proxy) | Warm proxy | Offline | Cached files |
|---|---|---|---|---|---|
| TicTacToe-SpringBoot-Kata | Maven 3.9 `verify` (incl. tests) | 67 s ✅ | — | 5 s ✅ | 900 |
| DriveSmart (CMP + Spring Boot `:server`) | Gradle 8.14.2 `:server:bootJar :composeApp:assembleDebug :shared:compileKotlinIosSimulatorArm64` | 774 s ✅ | 127 s ✅ | 112 s ✅ ¹ | 3163 |

¹ After adding a GitHub Packages upstream (below). Without it the offline run failed.

Cold and warm times include compiling; the difference is almost entirely download time.

## Findings

1. **Private repositories need an upstream, or offline builds fail.** DriveSmart's version catalog
   `io.github.gowthamraj07:cmp-catalog` lives in GitHub Packages. The proxy answered 404, Gradle fell back to the
   build's own repository (correct online), and offline there was nothing left to ask. Adding the upstream fixed it:

   ```yaml
   - name: github-mobile-deps
     url: https://maven.pkg.github.com/gowthamraj07/mobile-deps
     includes: [ "io/github/gowthamraj07/**" ]
     credentials: { username-env: GPR_USER, password-env: GPR_KEY }
   ```

   At the time that meant copying all default upstreams into a file passed with `--spring.config.additional-location`
   and exporting the credentials. **Since fixed (goal 14):** the entry above goes under `localrepo.extra-upstreams` in
   `~/.localrepo/upstreams.yml` (or is added on the Upstreams page), with
   `credentials: { gradle-property-username: gpr.user, gradle-property-password: gpr.key }` read from
   `~/.gradle/gradle.properties`. Re-run that way, without extra arguments or exported secrets: online 126 s,
   offline 112 s ✅.
2. **Overriding one upstream field on the command line killed the server** with a bare NullPointerException (Spring
   replaces lists as a whole). Fixed: a clear startup error naming the missing field (`02b62c1`).
3. **Reinstalling the Maven mirror over a settings.xml LocalRepoServer had created failed** (`9869764`, fixed).
4. Kotlin/Native's compiler (`kotlin-native-prebuilt`) now comes from Maven Central and was served by the proxy. Its
   LLVM/libffi dependencies were already in `~/.konan`, which the pilot did not empty; a machine without them would
   still fetch those from download.jetbrains.com.
5. Expected noise: Gradle probes a `.jar` for every plugin marker (markers are POM-only) and some optional `.module`
   files; these 404s are remembered by the negative cache.

## Re-running

```
./mvnw package -DskipTests
scripts/smoke.sh --maven ../TicTacToe-SpringBoot-Kata verify
# uses ~/.localrepo/upstreams.yml (or CONFIG_DIR=...) for the GitHub Packages upstream
scripts/smoke.sh ../../StudioProjects/DriveSmart :server:bootJar :composeApp:assembleDebug
```
