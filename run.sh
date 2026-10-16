#!/usr/bin/env bash
# Usage: ./run.sh [project-dir]   (no argument = main window without tabs, with one = opens it directly)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Incremental: a no-op when nothing changed, so the app never runs a stale build.
(cd "$here" && ./gradlew -q installDist)
exec "$here/build/install/fabric-manager/bin/fabric-manager" "$@"
