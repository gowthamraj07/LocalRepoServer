# LocalRepoServer — project status, 2026-10-22

**Commit** `bec2c53` (`master`, tag `v2.0.2`, in sync with `origin/master`) · **Group** Infra ·
**Run** 1 of this audit — **first run, so there is no delta to report.** Every number below is a
baseline; the next run computes movement against `docs/status/scores.csv`.

## Preflight caveats — read before acting on the numbers

1. **The knowledge graph is stale and partial, so it was not used as evidence.**
   `list_graph_stats` reports **20 files, 181 nodes, last updated 2026-10-08T15:06** against
   **76 `.java` files and 13 Thymeleaf templates** on disk. That is roughly a quarter of the tree,
   indexed 14 days ago. It was *not* rebuilt (this audit ran alongside 15 others and must not
   disturb the repo), so **all structural evidence here comes from `find`/`grep` over the working
   tree**. `CLAUDE.md`'s own claim ("16 files, ~125 nodes, ~585 edges as of 2026-10-07") is also
   out of date — a small documentation drift worth fixing when the graph is next rebuilt.
2. **No spec layer.** There is no `openspec/` directory, so there is no behavioural contract to
   count requirements from. The module table below was derived by hand (see *Method*), and
   **Behaviour was judged, not counted**.
3. **Working tree is clean**, nothing uncommitted, nothing unpushed, the released tag matches
   `pom.xml` (2.0.2). Nothing is being scored as shipped that is not actually shipped.
4. **No build or test suite was run and the server was not started.** The Verification dimension
   is scored from test *files on disk* mapped to behavioural units, plus the test method names
   those files contain — never from a test run.

---

## The objective

From project memory, `goal-00-roadmap.md` (the user's own words, planned 2026-10-07) — quoted
verbatim:

> Aim: make LocalRepoServer a reliable caching proxy for Maven, Gradle, Android and KMP/CMP
> projects. It should cut network calls, keep builds working offline or on bad connections, make
> dependencies portable, and need **no per-project config**.

Locked decisions from the same file, which the development plan must not reopen:

> - Runs on the laptop only (localhost, no auth, no Docker/Pi).
> - Java 21 + Spring Boot 3.x, staying on Maven (not Kotlin).
> - UI is Thymeleaf + htmx inside the same jar.
> - Start a fresh cache. Drop Derby, no migration of the old `local_repo/`.

`README.md` and `CLAUDE.md` agree with this objective; there is no conflict to report.

### Alignment axes

Three axes are the objective's own clauses, plus one for the enablers it does not name:

| Axis | The objective's words | Meaning |
|---|---|---|
| **C — Caching & resilience** | "cut network calls, keep builds working offline or on bad connections" | A build gets its bytes once, and keeps building when the network or an upstream is gone |
| **P — Portability** | "make dependencies portable" | The cache can be carried to another machine or another disk |
| **Z — Zero-config integration** | "need **no per-project config**" | Existing Maven/Gradle/Android/KMP projects work unchanged |
| **PL — Platform** | (enabler, not stated) | API, UI, configuration, packaging, service, release |

A future run must use these same four axes or the deltas are meaningless.

---

## Headline

| | |
|---|---|
| **Overall** | **95%** |
| C — Caching & resilience | 95% |
| P — Portability | 95% |
| Z — Zero-config integration | 90% |
| PL — Platform | 95% |

Delta vs previous run: **none — this is the first run.**

Weighted by requirement count over the 14 non-deferred modules (Σ reqs = 91);
4 deliberate exclusions are outside every rollup.

The project is a released, genuinely finished service: 169 commits (53 of them in October 2026),
185 test methods across 25 test classes, a CI job that resolves through a **real Gradle build**,
a CLI acceptance script with 23 checks, and a recorded pilot in which 30 of the user's own
projects built from this cache alone. The headline is high because the evidence says so, not
because the module list is short. What is left is narrow and named below.

---

## Module table

One row per behaviour a user of this server would name. Sorted by axis, then **least complete
first**.

| Module | Axis | Score | Label | reqs | bugs | gaps | tests (files · methods) | Surface | What's missing |
|---|---|---|---|---|---|---|---|---|---|
| **C3 Upstream routing & miss handling** | C | **90%** | 🟢 Shipped, with gaps | 7 | 1 | 1 | `RepositoryTest` 8, `NegativeCacheTest` 4, `ArtifactServiceTest` 32 | `/cache/**`, `/repo/{name}/**`, Upstreams page | `maven-metadata.xml` is served from the first upstream that has it, never merged across the group |
| **C1 Artifact serving & cache store** | C | **95%** | ✅ Complete | 8 | 0 | 0 | `ArtifactServiceTest` 32, `ArtifactControllerTest` 20, `ArtifactStoreTest` 15, `ArtifactPathTest` 5 | `/cache/**`, `/group/**`, Artifacts page | `ContentTypes` has no direct test; raw 94–96 band, at the bottom edge of Complete |
| **C4 Freshness, revalidation & offline mode** | C | **95%** | ✅ Complete | 7 | 0 | 2 | `FreshnessPolicyTest` 4, `ArtifactControllerTest` (offline, stale), `ArtifactServiceTest` | `/api/offline`, `localrepo offline`, UI header switch | Version ranges still need the switch flipped by hand; the Kotlin/Native toolchain is not proxied at all |
| **C6 Cache maintenance: size limit, purge, prefetch** | C | **95%** | ✅ Complete | 6 | 0 | 1 | `CacheEvictorTest` 6, `PrefetchListTest` 5, `MaintenanceControllerTest` 5 | Maintenance page, `/api/purge`, `/api/evict`, `/api/prefetch` | The Maintenance page only *documents* `localrepo.max-size`; it cannot set it |
| **C2 Streaming pass-through & concurrent downloads** | C | **100%** | ✅ Complete | 5 | 0 | 0 | `DownloadTest` 5, `DownloadCoordinatorTest` 2, `DownloadTrackerTest` 3, `ArtifactControllerTest` (truncation, abort) | `/cache/**` while downloading, Downloads page | — |
| **C5 Integrity: checksums, quarantine, cache scan** | C | **100%** | ✅ Complete | 5 | 0 | 0 | `ArtifactServiceTest` (checksum, quarantine), `ArtifactStoreTest` 15, `ArtifactControllerTest` (`verifiesTheWholeCacheAndDropsCorruptFiles`) | `POST /api/verify`, Setup page | — (quarantined files have no UI; see *Unspecified surface*) |
| **P2 Cache location: move-cache & disk resilience** | P | **95%** | ✅ Complete | 6 | 0 | 1 | `CacheLocationTest` 3, `ApiControllerTest` (`isDownWhileTheCacheDirectoryIsGone`), `UiControllerTest` (dashboard warning), `scripts/test-cli.sh` 4 checks | `localrepo move-cache`, `/actuator/health`, dashboard | The macOS removable-volume permission path and launchd-before-mount case are not exercised anywhere (CI is Linux-only) |
| **P1 Bundles: export & import** | P | **100%** | ✅ Complete | 5 | 0 | 0 | `MaintenanceControllerTest` (export, export-by-age, tamper/traversal rejection), `UiControllerTest` (`importsAnUploadedBundle`), `test-cli.sh` 2 checks | `localrepo export/import`, `/api/export`, `/api/import`, Maintenance page | — (`CacheBundles` has no direct unit test, only end-to-end) |
| **Z2 Maven integration (global + per-project)** | Z | **80%** | 🟢 Shipped, with gaps | 6 | 1 | 1 | `MavenSettingsEditorTest` 8, `SetupControllerTest` 8 | `localrepo install-maven`, `/setup/maven/settings.xml`, Setup page | Maven has no fallback: a stopped server or a missing cache disk fails every Maven build. No real-Maven resolution test runs in CI (`scripts/smoke.sh --maven` is manual) |
| **Z1 Gradle integration (global + per-project)** | Z | **95%** | ✅ Complete | 7 | 0 | 1 | `GradleInitScriptTest` 9 (**real Gradle builds**, incl. version range offline, exclusive mode, server-down step-aside, `-Plocalrepo.disabled`), `SetupControllerTest` | `localrepo install-gradle`, `/setup/gradle`, Setup page | Only the Groovy-DSL fixture on one Gradle version runs in CI; Kotlin DSL, AGP 9.2 and CMP 1.12 were verified by hand in the pilot |
| **L4 Packaging, CLI & login service** | PL | **90%** | 🟢 Shipped, with gaps | 9 | 0 | 2 | `scripts/test-cli.sh` 23 checks (in CI), release workflow tag/version guard | `install.sh`, `localrepo` (15 verbs), launchd, GitHub release | No supervised service on Linux (`service install` is macOS-only); `service install`, `logs` and `open` have no automated coverage, and CI runs on `ubuntu-latest` only |
| **L3 Configuration & private upstreams** | PL | **100%** | ✅ Complete | 6 | 0 | 0 | `LocalRepoPropertiesTest` 6 (incl. credentials read from a written `gradle.properties`), `UpstreamEditorTest` 3, `UiControllerTest` (add/remove upstream) | `~/.localrepo/config.yml`, `upstreams.yml`, Upstreams page | — |
| **L2 Web UI** | PL | **100%** | ✅ Complete | 7 | 0 | 0 | `UiControllerTest` 13 (every page, both fragments and 403 paths), `FormatTest` 2 | 6 pages + 7 htmx fragments, `localrepo open` | — |
| **L1 JSON API & live events** | PL | **100%** | ✅ Complete | 7 | 0 | 0 | `ApiControllerTest` 10 (incl. SSE stream and health 503), `AccessStatsTest` 5, `MavenCoordinatesTest` 3 | `/api/*` (12 endpoints), `/api/events` SSE, `/actuator/health` | — |

**Labels:** 11 ✅ Complete · 3 🟢 Shipped-with-gaps · 0 🟡 Partial · 0 🟠 Started · 0 ⬜ Not-started ·
4 ⛔ Deferred (excluded).

Five of the Complete modules (C1, C4, C6, P2, Z1) sit in the raw 94–96 band and are Complete only
after rounding; each carries the named residue in its row. Two of them (C4, P2) also carry a Low
correctness item — that is a contradiction worth seeing rather than averaging away, and it is why
those rows name the gap explicitly.

### Dimension breakdown (so the scores can be checked, not trusted)

| Module | Behaviour /50 | Correctness /20 | Verification /20 | Reach /10 | Raw | Rounded |
|---|---|---|---|---|---|---|
| C1 | 50 (8/8) | 20 | 16 (4/5 units) | 10 | 96.0 | 95 |
| C2 | 50 (5/5) | 20 | 20 (4/4) | 10 | 100.0 | 100 |
| C3 | 46.4 (6.5/7) | 15 (−5 Medium) | 20 (3/3) | 10 | 91.4 | 90 |
| C4 | 46.4 (6.5/7) | 18 (−2 Low) | 20 (3/3) | 10 | 94.4 | 95 |
| C5 | 50 (5/5) | 20 | 20 (3/3) | 10 | 100.0 | 100 |
| C6 | 45.8 (5.5/6) | 20 | 20 (4/4) | 10 | 95.8 | 95 |
| P1 | 50 (5/5) | 20 | 20 (3/3) | 10 | 100.0 | 100 |
| P2 | 50 (6/6) | 18 (−2 Low) | 16 (4/5) | 10 | 94.0 | 95 |
| Z1 | 50 (7/7) | 20 | 15 (3/4) | 10 | 95.0 | 95 |
| Z2 | 41.7 (5/6) | 15 (−5 Medium) | 15 (3/4) | 10 | 81.7 | 80 |
| L1 | 50 (7/7) | 20 | 20 (6/6) | 10 | 100.0 | 100 |
| L2 | 50 (7/7) | 20 | 20 (3/3) | 10 | 100.0 | 100 |
| L3 | 50 (6/6) | 20 | 20 (4/4) | 10 | 100.0 | 100 |
| L4 | 44.4 (8/9) | 20 | 16 (4/5) | 10 | 90.4 | 90 |

Reachability is 10 everywhere because this service is operated two ways that both exist and are
tested: the `localrepo` CLI (15 verbs, 23 CI-run checks) and the web UI (6 pages). Nothing in the
table is code that only a raw HTTP call can reach.

---

## Correctness debt

Kept separate from completeness on purpose: a proxy that answers *confidently wrong* is worse
than one that is honestly missing a feature.

### High

None. The three root-cause bugs recorded in `goal-00-roadmap.md` (the `new Thread` partial
download served forever, the empty `200` on a miss that stopped Gradle falling through, and the
5-second read timeout that killed large downloads) are all fixed and each has a regression test —
`ArtifactControllerTest.neverPresentsATruncatedUpstreamBodyAsComplete`, the `Missing → 404` case,
and `localrepo.read-idle-timeout: 60s`.

### Medium

| # | Module | Item | Consequence |
|---|---|---|---|
| M1 | C3 | **`maven-metadata.xml` is not merged across the group.** `/cache/**` asks upstreams in order and returns the first body that exists; there is no merge step anywhere in `artifact/` (confirmed by grep: the only `merge` calls in `src/main` are in `AccessStats`). | A dynamic or ranged version (`[1.80,1.81)`, `1.+`) whose candidate versions are split between two upstreams resolves against an incomplete version list. In offline-exclusive mode — which 2.0.2 now switches on automatically — the build has no other repository to ask, so it either picks the wrong version or fails. This is the one failure mode the pilot already flagged as the offline sore point. |
| M2 | Z2 | **Maven has no fallback while the mirror is installed.** `mirrorOf *` sends everything to the server, so a stopped server, a crashed JVM or a disconnected cache disk fails every Maven build. Gradle's init script steps aside in exactly these cases; Maven cannot. | Directly contradicts the objective's "builds keep working when the network is slow, flaky or gone" for the Maven half of the fleet. It is documented in the README and in the external-disk section, but documentation is not resilience. |

### Low

| # | Module | Item |
|---|---|---|
| L-a | C4 | The Kotlin/Native toolchain is not proxied. `kotlin-native-prebuilt` comes from Central and is cached, but its LLVM/libffi payload comes from `download.jetbrains.com` and is not a Maven artifact, so a fresh machine still needs the network for a KMP build (`docs/pilot-2026.md`, finding 4). |
| L-b | C4 | Without the offline switch, builds with version ranges fail while the network is gone. 2.0.2 makes offline mode exclusive automatically, but the switch is still a manual act at exactly the moment the user has no connectivity. |
| L-c | P2 | macOS asks once for removable-volume access and the login service silently waits for the answer. The only remedy is the documented System Settings walkthrough. |
| L-d | C6 | The Maintenance page lists "size limit" but only renders `localrepo.max-size` and tells the reader to edit `config.yml`; `max-size` is also unset by default, so the cache grows without bound until someone configures it. |
| L-e | C1/C2 | `ContentTypes`, `UpstreamResponse` and `RequestListener` appear in no test file by name. `ContentTypes` decides the `Content-Type` of every served artifact and is the only one with real branching. |
| L-f | — | Derby-era leftovers are still on disk: `local_repo/` (614 directories, March 2024) and `directory/` with a live `db.lck`. Both are `.gitignore`d, so this is local clutter rather than shipped code, but it is 2.5 MB of a decision ("drop Derby, no migration") that was never swept up. |
| L-g | — | `CLAUDE.md` describes the graph as "16 files, ~125 nodes, ~585 edges as of 2026-10-07"; the index actually holds 20 files / 181 nodes / 1424 edges and is 14 days stale against 76 `.java` files. Documentation and reality have drifted. |

---

## Unspecified surface

Behaviour that exists but no module or document above tracks as a capability:

- **`.quarantine` has no API and no UI.** Rejected downloads are moved to
  `~/.localrepo/cache/.quarantine` and never served, which is right — but nothing lists them, counts
  them or clears them. The only way to learn that an upstream is serving corrupt bytes is to look at
  the filesystem or the log.
- **`scripts/smoke.sh`** is a real acceptance harness (it proves a project builds offline from the
  server alone, for Gradle and Maven) that nothing runs automatically. It is the evidence behind
  `docs/pilot-2026.md`, and that evidence is now dated 2026-10-07/08.
- **The pilot result set itself** — 30 of the user's projects verified offline on 2026-10-08
  (project memory, `user-projects-on-localrepo.md`) — is the strongest validation this project has
  and lives only in memory and a dated doc, not in any check that would notice it going stale.
- **`ApiConfiguration` / `MaintenanceConfiguration`** wiring is covered only by the Spring context
  load in `ServerApplicationTests`.

---

## Deliberate exclusions (⛔ Deferred — excluded from every rollup)

| Exclusion | Rationale (locked 2026-10-07) |
|---|---|
| Authentication, multi-user, non-localhost binding | "Runs on the laptop only (localhost, no auth…)". `server.address: 127.0.0.1` plus the `X-LocalRepo-Action` header is the whole security model, and it is consistent across all 23 mutating endpoints. |
| Docker / Raspberry Pi hosting | "…no Docker/Pi." Scoring a non-goal at 0% would drag the rollup down and quietly reopen a settled decision. |
| Migration of the pre-2.0 Derby store | "Start a fresh cache. Drop Derby, no migration of the old `local_repo/`." |
| Native Windows support | README: "on Windows, run it under WSL". |

Nothing in the plan below contradicts these four.

---

## Development plan

Ranked by `axis_weight × (100 − score) ÷ 100` with Z = 3 (the axis furthest from done), C = 2,
P = 2, PL = 1 — and the two overrides the rubric requires: correctness debt jumps the queue, and a
blocked prerequisite outranks its dependent.

### Now — correctness debt and anything one session closes

1. **Merge `maven-metadata.xml` across the group** — *C3, 90%, axis C, debt M1.*
   `/cache/**` asks every eligible upstream for the metadata and merges `<versions>` (union, ordered,
   `<latest>`/`<release>` recomputed) before caching the result; `/repo/{name}/**` keeps the
   single-upstream behaviour. Outcome: a ranged or dynamic version resolves against every version the
   group can serve, including in offline-exclusive mode. Next action:
   *"Serve `/cache` `maven-metadata.xml` as the merged version list of every matching upstream, not
   the first hit."*
2. **Tell the user, loudly, when Maven builds are about to fail** — *Z2, 80%, axis Z, debt M2.*
   `localrepo status` and the dashboard already know the server is up and whether the cache disk is
   present; make them state the consequence (`maven: installed — Maven builds will fail while the
   cache disk is missing`), and have `install-maven` print the one-line escape hatch. Outcome: the
   known-unfixable Maven limitation stops being a silent build break. Next action:
   *"Make `localrepo status`, the dashboard and `install-maven` say that Maven builds fail while the
   server or its cache disk is unavailable, and how to get out of it."*
3. **Put a real Maven resolution test in CI** — *Z2, 80%, Verification 3/4.*
   A `MavenRunner` beside the existing `GradleRunner`, driving a fixture `pom.xml` through the
   generated `settings.xml` — the Gradle side already does exactly this in `GradleInitScriptTest` and
   it is the single highest-value missing test in the repo. Next action:
   *"Resolve a fixture Maven project through the generated mirror settings in CI, the way
   `GradleInitScriptTest` already does for Gradle."*
4. **Let the Maintenance page set the size limit** — *C6, 95%, debt L-d.*
   The page already renders `max-size`; make the field writable through `UpstreamEditor`-style
   persistence into `config.yml`, and say in the UI that the cache is unbounded while it is unset.
   Next action: *"Make the cache size limit editable on the Maintenance page instead of documented
   there."*
5. **Surface the quarantine** — *C5, 100% but invisible, see Unspecified surface.*
   A count on the dashboard and a list under Setup → Check the cache, with a clear-all action.
   Outcome: a misbehaving upstream becomes visible instead of silent. Next action:
   *"Show quarantined files and their count in the UI and in `/api/stats`."*

### Next (1–2 months)

1. **A macOS CI job** — *P2 and L4 (both 90–95%) are verified only on `ubuntu-latest`, yet the
   product's real home is macOS with launchd and an external APFS disk.* Run `./mvnw verify` plus
   `scripts/test-cli.sh` on `macos-latest`, and add `service install`/`uninstall` to the CLI script.
2. **A supervised service on Linux** — *L4, 90%, the one unmet requirement of its nine.* systemd user
   unit behind the same `localrepo service install` verb, so the README's "macOS or Linux" promise is
   true for the service too.
3. **Re-run the pilot as a dated regression** — *unspecified surface.* `scripts/smoke.sh` over the
   30-project list in project memory, written back into `docs/pilot-2026.md`. 2.0.2 changed offline
   exclusivity after the last verification, so that evidence is now one release old.
4. **Close the Kotlin/Native hole** — *C4, debt L-a.* Either proxy `download.jetbrains.com` as a
   non-Maven passthrough upstream, or add a `localrepo prefetch --konan` that seeds `~/.konan` before
   going offline. This is the last reason a KMP build still needs the network on a fresh machine.
5. **Widen the Gradle matrix** — *Z1, 95%, Verification 3/4.* Add a Kotlin-DSL fixture and one AGP/KMP
   fixture to `GradleInitScriptTest`, so the combinations the pilot verified by hand are the ones CI
   verifies.

### Later

- **Adopt OpenSpec as the planning layer** (`openspec-integrate`). This audit had to invent its own
  module list and judge Behaviour by reading code against the README; with capability specs the next
  run counts requirements instead of judging them, and becomes genuinely comparable rather than
  merely repeatable.
- **Revisit two deferred decisions, explicitly and only if the user wants to.** Serving the cache to
  a second machine on the LAN (needs the localhost/no-auth decision reopened) and running it on the
  Pi (needs the "laptop only" decision reopened). Both are settled; neither should move without the
  user saying so.
- **Observability beyond `/api/stats`** — a Prometheus endpoint, and retention of hit-rate history so
  the dashboard can show a trend rather than a total.
- **Sweep the Derby leftovers** (debt L-f) and drop `/directory/` and `/local_repo/` from
  `.gitignore` once they are gone.
- **Refresh the graph and `CLAUDE.md`'s description of it** (debt L-g), so the next audit can use it.

---

## Method — so the next run reproduces this one

**Module list.** No `openspec/specs/` exists, so the module table was built by hand from
`README.md`'s own capability list — its "Contents", "Day-to-day use", "Configuration",
"Command reference" and "API" sections — cross-checked against the 14 roadmap goals in project
memory (`goal-00-roadmap.md`) and against the user-visible surface actually present in the tree
(46 `@*Mapping` routes, 13 Thymeleaf templates, 15 `localrepo` CLI verbs). Build modules and Java
packages were **not** used: `api/`, `artifact/`, `config/`, `maintenance/`, `setup/`, `ui/` describe
layering, and the objective is about behaviour. Each module maps to the files named in its evidence
column; the package tree supplies file and test counts underneath, nothing more.

**Behaviour was judged, not counted.** Without specs there is no requirement list to divide by, so
each module's `reqs` is the number of distinct promises the README (and the CLI/API reference) makes
about that module, enumerated during this audit, and Behaviour is
`50 × (promises honoured ÷ promises)` with a promise that is *built but contradicted* counted as a
half. The enumeration is recorded in the module rows and the debt table; a future run should re-read
those rows rather than re-derive the counts, or the denominators will drift.

**Rubric (fixed — do not change between runs).**

| Dimension | Max | Rule |
|---|---|---|
| Behaviour | 50 | `50 × (promises honoured ÷ promises)`; built-but-contradicted counts as a half |
| Correctness | 20 | From 20: −7 High, −5 Medium, −2 Low per item touching the module; −2 per gap flagged as a defect. Floor 0 |
| Verification | 20 | `20 × (behavioural units with a test ÷ behavioural units)`. A unit counts as verified when a test file on disk asserts its behaviour, by direct unit test *or* by an integration test whose assertions cover it (e.g. `CacheVerifier` has no unit test but `ArtifactControllerTest.verifiesTheWholeCacheAndDropsCorruptFiles` asserts its report, its deletion and the refetch). Units are services, controllers, policies and scripts — not records, DTOs, exceptions or `@Configuration` wiring. Where a platform-specific path cannot run in CI at all (launchd, macOS volume permissions, a real Maven build), that path is counted as an unverified unit |
| Reachability | 10 | 10 = reachable from the web UI or the `localrepo` CLI · 5 = HTTP only · 0 = nothing surfaces it |

Round each module to the nearest 5. Rollups are weighted by `reqs`:
`overall = Σ(score × reqs) ÷ Σ(reqs)` over non-deferred modules only (Σ reqs = 91).

**Evidence commands used** (graph unavailable, so all filesystem):
`git status --short`, `git log`, `git tag`, `find src -name '*.java'`,
`grep -rn '@GetMapping|@PostMapping|@DeleteMapping' src/main/java`,
`grep -c '@Test|@ParameterizedTest'` per test file, a class→test cross-reference over `src/test`,
`grep -rn 'TODO|FIXME|XXX|HACK' src bin scripts install.sh` (**zero hits**), and targeted greps for
every feature the README claims (`X-LocalRepo-Stale`, `quarantine`, checksum verification, `pinned`,
`maxSize`, negative caching, `verification-metadata`, credential resolution, `BAD_GATEWAY`, launchd).

**Score history.** One row per module appended to `docs/status/scores.csv`
(`date,module,axis,score,reqs,bugs,gaps`). The next run computes deltas from it and must report any
score that moves **down**, with the cause named.
