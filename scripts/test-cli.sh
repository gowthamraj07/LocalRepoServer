#!/usr/bin/env bash
# Exercises bin/localrepo and install.sh against a throwaway home, port and build-tool settings.
#   ./mvnw package -DskipTests && scripts/test-cli.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
jar=$(ls -t "$root"/target/server-*.jar | grep -v '\.original$' | head -1)
work=$(mktemp -d)
export LOCALREPO_HOME="$work/home" BIN_DIR="$work/bin" GRADLE_USER_HOME="$work/gradle"
export LOCALREPO_PORT=$((20000 + RANDOM % 20000))
export LOCALREPO_ARGS="--localrepo.maven-settings=$work/m2/settings.xml"
cli="$BIN_DIR/localrepo"

cleanup() {
    "$cli" stop > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

check() { # description, command...
    local description=$1
    shift
    if "$@" > "$work/last.out" 2>&1; then
        echo "ok   $description"
    else
        echo "FAIL $description"
        cat "$work/last.out"
        exit 1
    fi
}

check "install.sh installs the jar and the command" "$root/install.sh" --jar "$jar"
check "the jar is in place" test -f "$LOCALREPO_HOME/server.jar"
check "version reads the jar manifest" bash -c "'$cli' version | grep -q '^LocalRepoServer '"
check "status says it is not running" bash -c "'$cli' status; test \$? -eq 3"
check "start brings the server up" "$cli" start
check "start again is harmless" bash -c "'$cli' start | grep -q 'already running'"
check "status reports stats" bash -c "'$cli' status | grep -q '^  hitRate: '"
check "install-gradle writes the init script" bash -c "'$cli' install-gradle && test -f '$GRADLE_USER_HOME/init.d/localrepo.init.gradle'"
check "uninstall-gradle removes it" bash -c "'$cli' uninstall-gradle && test ! -f '$GRADLE_USER_HOME/init.d/localrepo.init.gradle'"
check "install-maven writes the mirror" bash -c "'$cli' install-maven && grep -q '<mirrorOf>\*</mirrorOf>' '$work/m2/settings.xml'"
check "uninstall-maven removes the settings it created" bash -c "'$cli' uninstall-maven && test ! -f '$work/m2/settings.xml'"
check "offline on" bash -c "'$cli' offline on | grep -q '\"enabled\":true'"
check "offline off" bash -c "'$cli' offline off | grep -q '\"enabled\":false'"
check "export writes a bundle" bash -c "cd '$work' && '$cli' export bundle.zip && unzip -l bundle.zip | grep -q localrepo-bundle.json"
check "import reads it back" bash -c "'$cli' import '$work/bundle.zip' | grep -q '\"imported\":0'"
check "config.yml is applied" bash -c "'$cli' stop && printf 'localrepo:\n  offline: true\n' > '$LOCALREPO_HOME/config.yml' && '$cli' start && curl -fs http://127.0.0.1:$LOCALREPO_PORT/api/offline | grep -q true"
check "stop stops it" bash -c "'$cli' stop && ! curl -fs --max-time 2 http://127.0.0.1:$LOCALREPO_PORT/actuator/health"
echo "all CLI checks passed"
