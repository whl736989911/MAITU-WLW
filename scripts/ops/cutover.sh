#!/usr/bin/env bash
set -Eeuo pipefail

# Isolated H2 snapshot -> PostgreSQL 17 cutover runner.
#
# Wraps the com.fireplatform.ops.H2PostgresCutover main class inside the packaged
# application JAR. The source H2 file is opened read-only and is never modified;
# the target PostgreSQL database must be PostgreSQL 17 with a completely empty
# public schema. The default action is a read-only plan; --execute is required
# before any target DDL/DML happens.
#
# The six FIRE_CUTOVER_* credentials are read from the environment only. This
# script never reads them from argv or files and never prints them.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
source "$ROOT/scripts/runtime-env.sh"

JAR=${FIRE_JAR:-"$ROOT/backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar"}
MAIN=com.fireplatform.ops.H2PostgresCutover
LAUNCHER=org.springframework.boot.loader.launch.PropertiesLauncher

usage() {
  cat <<EOF
Usage: bash scripts/ops/cutover.sh [--execute] --expected-version <n> --manifest-out <private-path>

Isolated H2 snapshot -> PostgreSQL 17 cutover (read-only plan unless --execute).

Required environment (source is the stopped H2 file, target is an empty PG17):
  FIRE_CUTOVER_SOURCE_URL        jdbc:h2:file:...;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE
  FIRE_CUTOVER_SOURCE_USERNAME   FIRE_CUTOVER_SOURCE_PASSWORD
  FIRE_CUTOVER_TARGET_URL        jdbc:postgresql://127.0.0.1:5432/<empty db>
  FIRE_CUTOVER_TARGET_USERNAME   FIRE_CUTOVER_TARGET_PASSWORD

Credentials are never read from argv or files and never printed.
The private manifest records versions, per-table counts/digests, FK validation
and committed=true only after the target transaction actually commits.
EOF
}

if [[ $# -eq 1 && ( $1 == --help || $1 == -h ) ]]; then
  usage
  exit 0
fi

[[ -f "$JAR" ]] || {
  printf 'Packaged JAR not found: %s\n' "$JAR" >&2
  printf 'Build it with bash scripts/package.sh or set FIRE_JAR to an existing executable JAR.\n' >&2
  exit 1
}

required_env() {
  local name=$1
  [[ -n ${!name:-} ]] || { printf 'Missing environment variable %s\n' "$name" >&2; exit 2; }
}
for name in FIRE_CUTOVER_SOURCE_URL FIRE_CUTOVER_SOURCE_USERNAME \
            FIRE_CUTOVER_SOURCE_PASSWORD FIRE_CUTOVER_TARGET_URL \
            FIRE_CUTOVER_TARGET_USERNAME FIRE_CUTOVER_TARGET_PASSWORD; do
  required_env "$name"
done

exec java -Dloader.main="$MAIN" -cp "$JAR" "$LAUNCHER" "$@"
