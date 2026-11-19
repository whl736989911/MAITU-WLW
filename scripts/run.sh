#!/usr/bin/env bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source "$ROOT/scripts/runtime-env.sh"
export APP_PORT=${APP_PORT:-8080}
export APP_PROFILE=${APP_PROFILE:-dev}
if [[ "$APP_PROFILE" == prod ]]; then
  export APP_STORAGE=${APP_STORAGE:-postgresql}
else
  export APP_STORAGE=${APP_STORAGE:-h2-dev}
fi
JAR=${FIRE_JAR:-"$ROOT/backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar"}
[[ -f "$JAR" ]] || { printf '%s\n' 'Build the package first or set FIRE_JAR to an existing executable JAR.' >&2; exit 1; }
cd "$ROOT/backend"
exec java -jar "$JAR"
