#!/usr/bin/env bash
# Finite, exclusive Linux validation driver. No service/system installation.
set -euo pipefail
ROOT=${FIRE_VALIDATION_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
[[ $(uname -s) == Linux && $(id -u) != 0 && -f "$ROOT/backend/pom.xml" ]] || { printf '%s\n' 'Non-root Ubuntu source workspace required.' >&2; exit 2; }
LOGDIR="$ROOT/artifacts/ubuntu-linux"
mkdir -p "$LOGDIR"
[[ ! -e "$LOGDIR/verify-01.log" && ! -e "$LOGDIR/package-01.log" ]] || { printf '%s\n' 'Refusing to overwrite validation logs.' >&2; exit 2; }
cd "$ROOT"
printf '%s\n' 'UBUNTU_VERIFY_START'
bash scripts/verify.sh > "$LOGDIR/verify-01.log" 2>&1
printf '%s\n' 'UBUNTU_VERIFY_PASS'
bash scripts/package.sh --skip-install > "$LOGDIR/package-01.log" 2>&1
printf '%s\n' 'UBUNTU_COMPLETE_PACKAGE_PASS'
