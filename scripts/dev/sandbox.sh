#!/usr/bin/env bash
# 模块沙箱：让每个模块在自己的副本里编译/跑测试，互不影响主树与他人。
# 用法：
#   scripts/dev/sandbox.sh init <module>
#   scripts/dev/sandbox.sh test <module> "<maven args>"
#   scripts/dev/sandbox.sh back <module> <相对路径> [<相对路径>...]
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
  printf '%s\n' 'This script must run inside WSL. Use:' \
    "  wsl -d Ubuntu-24.04 -u whl -- bash /mnt/d/烟感/fire-platform/scripts/dev/sandbox.sh $*" >&2
  exit 2
fi
[[ $# -ge 2 ]] || { printf '%s\n' 'usage: sandbox.sh init|test|back <module> [...]' >&2; exit 2; }
CMD=$1; MOD=$2; shift 2
[[ $MOD =~ ^[a-z0-9-]+$ ]] || { printf '%s\n' 'module must match [a-z0-9-]+' >&2; exit 2; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SANDBOX=/tmp/fire-sandbox/$MOD
case "$CMD" in
  init)
    rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
    # 只拷构建所需，绝不拷 artifacts/（截图与 JAR 很大，9p 上会超时）
    rsync -a --exclude 'target/' "$ROOT/backend" "$SANDBOX/"
    rsync -a "$ROOT/scripts" "$SANDBOX/"
    printf 'sandbox ready: %s\n' "$SANDBOX" ;;
  test)
    [[ -d "$SANDBOX/backend" ]] || { printf '%s\n' 'run init first' >&2; exit 2; }
    TOOLS=${FIRE_TOOLS_DIR:-/home/whl/fire-platform-n05-20261030/.tools/linux}
    export JAVA_HOME="$TOOLS/jdk-21"
    export PATH="$TOOLS/node-24.16.0/bin:$TOOLS/jdk-21/bin:$TOOLS/apache-maven-3.9.11/bin:$PATH"
    export LEGACY_APPENDIX=${LEGACY_APPENDIX:-/mnt/d/烟感/_incoming/omp-docs/appendix}
    export TZ=UTC PYTHONDONTWRITEBYTECODE=1
    cd "$SANDBOX/backend"
    mvn -o -B -ntp "$@" test ;;
  back)
    [[ $# -ge 1 ]] || { printf '%s\n' 'back needs at least one relative path' >&2; exit 2; }
    for path in "$@"; do
      [[ "$path" != /* && "$path" != *..* ]] || { printf 'refusing path: %s\n' "$path" >&2; exit 2; }
      [[ -e "$SANDBOX/$path" ]] || { printf 'missing in sandbox: %s\n' "$path" >&2; exit 2; }
      mkdir -p "$ROOT/$(dirname "$path")"
      rsync -a --exclude 'target/' "$SANDBOX/$path" "$ROOT/$(dirname "$path")/"
    done
    printf 'copied back: %s\n' "$*" ;;
  *) printf 'unknown command: %s\n' "$CMD" >&2; exit 2 ;;
esac
