#!/usr/bin/env bash
# Installs LocalRepoServer into ~/.localrepo and the `localrepo` command into ~/.local/bin.
#
#   curl -fsSL https://raw.githubusercontent.com/gowthamraj07/LocalRepoServer/master/install.sh | bash
#   ./install.sh --jar target/server-2.0.0.jar      # from a local build instead of the latest release
#
# Environment: LOCALREPO_HOME (default ~/.localrepo), BIN_DIR (default ~/.local/bin).
set -euo pipefail

repo=gowthamraj07/LocalRepoServer
home=${LOCALREPO_HOME:-$HOME/.localrepo}
bin_dir=${BIN_DIR:-$HOME/.local/bin}
local_jar=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar) local_jar=$2; shift 2 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

mkdir -p "$home" "$bin_dir"
if [[ -n "$local_jar" ]]; then
    source_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    cp "$local_jar" "$home/server.jar.new"
    cp "$source_dir/bin/localrepo" "$bin_dir/localrepo.new"
else
    url="https://github.com/$repo/releases/latest/download"
    curl -fsSL -o "$home/server.jar.new" "$url/localrepo-server.jar"
    curl -fsSL -o "$bin_dir/localrepo.new" "$url/localrepo"
fi
mv "$home/server.jar.new" "$home/server.jar"
chmod +x "$bin_dir/localrepo.new"
mv "$bin_dir/localrepo.new" "$bin_dir/localrepo"

echo "Installed LocalRepoServer into $home and the localrepo command into $bin_dir"
case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) echo "Add $bin_dir to your PATH to run 'localrepo' directly." ;;
esac
cat <<NEXT

Next:
  localrepo start              # or 'localrepo service install' to start it at every login
  localrepo install-gradle     # route every Gradle build through it
  localrepo install-maven      # and every Maven build
  localrepo open               # the web UI
NEXT
