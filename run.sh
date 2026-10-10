#!/usr/bin/env bash
# Usage: ./run.sh [project-dir]   (builds once, then starts Claude in project-dir)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin="$here/build/install/claude-terminal-fx/bin/claude-terminal-fx"
if [[ ! -x "$bin" ]]; then
  (cd "$here" && ./gradlew -q installDist)
fi
exec "$bin" "${1:-$PWD}"
