#!/usr/bin/env bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source "$ROOT/scripts/runtime-env.sh"
[[ $# -eq 0 || ( $# -eq 1 && $1 == --skip-install ) ]] || { printf '%s\n' 'Usage: bash scripts/package.sh [--skip-install]' >&2; exit 2; }
command -v mvn >/dev/null
command -v npm >/dev/null
[[ $(node --version) == v24.* ]] || { printf '%s\n' 'Node24 is required.' >&2; exit 1; }
if [[ $# -eq 0 ]]; then
    npm ci --prefix "$ROOT/scripts/codec-sandbox"
    (cd "$ROOT/frontend" && npm ci)
fi
(cd "$ROOT/frontend" && npm test && npm run build)
(cd "$ROOT/backend" && mvn -B clean package)
printf '%s\n' 'Packaged Java21 executable JAR with frontend assets and full backend tests under backend/target.'
