#!/usr/bin/env bash
# Optional real-browser test tooling only, not an application runtime dependency.
set -euo pipefail
[[ $(uname -s) == Linux ]] || { printf '%s\n' 'Ubuntu/Linux is required.' >&2; exit 1; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
[[ $# -eq 0 || ( $# -eq 1 && $1 == --download-chromium ) ]] || { printf '%s\n' 'Usage: bash scripts/prepare-browser.sh [--download-chromium]' >&2; exit 2; }
TOOLS_ROOT=${FIRE_TOOLS_DIR:-"$ROOT/.tools/linux"}
if [[ -x "$TOOLS_ROOT/node-24.16.0/bin/node" ]]; then export PATH="$TOOLS_ROOT/node-24.16.0/bin:$PATH"; fi
[[ $(node --version) == v24.* ]] || { printf '%s\n' 'Node24 is required.' >&2; exit 1; }
TOOLS=${FIRE_BROWSER_TOOLS_DIR:-"$ROOT/.tools/browser-tools"}
mkdir -p "$TOOLS"
npm install --prefix "$TOOLS" --no-save --package-lock=false --ignore-scripts playwright@1.64.1
node "$TOOLS/node_modules/playwright/cli.js" --version
if [[ $# -eq 1 ]]; then
  export PLAYWRIGHT_BROWSERS_PATH=${PLAYWRIGHT_BROWSERS_PATH:-"${FIRE_TOOLS_DIR:-$ROOT/.tools/linux}/ms-playwright"}
  node "$TOOLS/node_modules/playwright/cli.js" install chromium
  printf '%s\n' 'Browser downloaded only to the selected project-local cache. Set BROWSER_EXECUTABLE to its absolute chrome binary; no apt/service/security-policy changes were made.'
else
  printf '%s\n' 'Set BROWSER_EXECUTABLE to an installed Ubuntu Chrome/Chromium executable before --browser tests.'
fi
