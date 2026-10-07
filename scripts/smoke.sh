#!/usr/bin/env bash
# Proves a project builds from LocalRepoServer alone.
#
#   scripts/smoke.sh [--maven] <project-dir> <gradle tasks | maven goals...>
#
# 1. online:  starts a private server, routes the build through it (Gradle init script or Maven mirror) with empty
#             build-tool caches, and builds. This fills the server's cache.
# 2. offline: restarts the server with every upstream pointing at a dead address, empties the build-tool caches
#             again and blocks the build tool from the internet (Gradle: dead system proxy; Maven: mirrorOf * means it
#             only talks to the server). The build must still pass, served entirely from the server's cache.
#
# Nothing in your real ~/.gradle, ~/.m2 or the project is changed. Your ~/.gradle/gradle.properties is copied into the
# throwaway Gradle home (mode 600) so private repository credentials keep working.
#
# Environment: WORK (default $TMPDIR/localrepo-smoke/<project>), PORT (default 18090), LOCALREPO_JAR (default the
# newest target/server-*.jar), KEEP_CACHE=1 to reuse the server cache from an earlier run.
set -euo pipefail

maven=false
if [[ "${1:-}" == "--maven" ]]; then
    maven=true
    shift
fi
if [[ $# -lt 2 ]]; then
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
project=$(cd "$1" && pwd)
shift

repo_root=$(cd "$(dirname "$0")/.." && pwd)
jar=${LOCALREPO_JAR:-$(ls -t "$repo_root"/target/server-*.jar 2>/dev/null | grep -v '\.original$' | head -1)}
[[ -f "$jar" ]] || { echo "No server jar; run ./mvnw package first" >&2; exit 1; }
port=${PORT:-18090}
work=${WORK:-${TMPDIR:-/tmp}/localrepo-smoke/$(basename "$project")}
base="http://127.0.0.1:$port"
dead="http://127.0.0.1:9"
server_pid=""

mkdir -p "$work"
[[ "${KEEP_CACHE:-}" == 1 ]] || rm -rf "$work/cache"
log() { printf '\n== %s\n' "$*"; }

stop_server() {
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
        server_pid=""
    fi
}
trap stop_server EXIT

start_server() { # extra server arguments...
    stop_server
    java -jar "$jar" --server.port="$port" --localrepo.cache-dir="$work/cache" \
        --localrepo.gradle-user-home="$work/gradle-home" --localrepo.maven-settings="$work/settings.xml" \
        "$@" > "$work/server-$phase.log" 2>&1 &
    server_pid=$!
    for _ in $(seq 1 60); do
        curl -fs "$base/actuator/health" > /dev/null 2>&1 && return
        sleep 1
    done
    echo "Server did not start; see $work/server-$phase.log" >&2
    exit 1
}

offline_upstreams() { # the online server's upstreams, by name, all pointing at a dead address
    local i=0 name
    # Spring replaces a configured list as a whole, so each upstream needs its name as well as the new url.
    for name in $(grep -o 'Upstream Repository\[[^ ]*' "$work/server-online.log" | sed 's/.*\[//'); do
        printf -- '--localrepo.upstreams[%d].name=%s\n--localrepo.upstreams[%d].url=%s\n' "$i" "$name" "$i" "$dead"
        i=$((i + 1))
    done
}

cached_files() {
    find "$work/cache" -type f ! -name '*.meta.json' 2>/dev/null | wc -l | tr -d ' '
}

run_build() {
    local started status=0
    started=$(date +%s)
    if $maven; then
        (cd "$project" && ${MVN:-$( [[ -x ./mvnw ]] && echo ./mvnw || echo mvn )} -B -s "$work/settings.xml" \
            -Dmaven.repo.local="$work/m2-repository" "$@") > "$work/build-$phase.log" 2>&1 || status=$?
    else
        (cd "$project" && GRADLE_USER_HOME="$work/gradle-home" ./gradlew --no-daemon --console=plain "$@") \
            > "$work/build-$phase.log" 2>&1 || status=$?
    fi
    elapsed=$(( $(date +%s) - started ))
    return $status
}

prepare_tool() {
    if $maven; then
        rm -rf "$work/m2-repository"
        curl -fs -X POST -H 'X-LocalRepo-Action: true' "$base/setup/maven/install" > /dev/null
    else
        mkdir -p "$work/gradle-home"
        rm -rf "$work/gradle-home/caches" "$work/gradle-home/daemon"
        local props="$work/gradle-home/gradle.properties"
        rm -f "$props"
        if [[ -f "$HOME/.gradle/gradle.properties" ]]; then
            install -m 600 "$HOME/.gradle/gradle.properties" "$props"
        fi
        if [[ "$phase" == offline ]]; then
            {
                echo
                echo "# localrepo smoke test: only localhost is reachable"
                for scheme in http https; do
                    echo "systemProp.$scheme.proxyHost=127.0.0.1"
                    echo "systemProp.$scheme.proxyPort=9"
                done
                echo "systemProp.http.nonProxyHosts=localhost|127.0.0.1"
            } >> "$props"
            chmod 600 "$props"
        fi
        curl -fs -X POST -H 'X-LocalRepo-Action: true' "$base/setup/gradle/install" > /dev/null
    fi
}

phase=online
log "online: $project $*"
start_server
prepare_tool
if run_build "$@"; then online_result=PASS; else online_result=FAIL; fi
online_time=$elapsed
online_files=$(cached_files)
echo "$online_result in ${online_time}s, server cache now holds $online_files files"

phase=offline
log "offline: upstreams unreachable, build tool caches empty"
dead_args=()
while IFS= read -r arg; do dead_args+=("$arg"); done < <(offline_upstreams)
start_server "${dead_args[@]}"
prepare_tool
if run_build "$@"; then offline_result=PASS; else offline_result=FAIL; fi
offline_time=$elapsed
echo "$offline_result in ${offline_time}s"

log "summary"
printf '%-8s %-5s %6ss\n' online "$online_result" "$online_time" offline "$offline_result" "$offline_time"
echo "cached files: $online_files; misses while offline: $(grep -c 'not found in' "$work/server-offline.log" || true)"
echo "logs: $work/{build,server}-{online,offline}.log"
[[ "$online_result" == PASS && "$offline_result" == PASS ]]
