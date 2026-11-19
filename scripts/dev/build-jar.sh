#!/usr/bin/env bash
# 构建可执行 JAR（跳过测试），供冒烟脚本使用。
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo 'run inside WSL' >&2; exit 2; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
source "$ROOT/scripts/runtime-env.sh"
[[ -f "$ROOT/frontend/dist/index.html" ]] || { printf '%s\n' 'Build frontend/dist in this checkout first.' >&2; exit 1; }
cd "$ROOT/backend"
mvn -o -B -ntp -DskipTests clean package
ls -l target/fire-platform-backend-*.jar
