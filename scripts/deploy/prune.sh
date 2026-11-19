#!/usr/bin/env bash
# 只保留最近 N 个发布版本（默认 2），永不删除 current 指向的版本。
set -euo pipefail
BASE=${FIRE_BASE:-$HOME/fire-platform}
KEEP=${1:-2}
CURRENT=$(readlink -f "$BASE/current")
cd "$BASE/releases"
ls -1dt */ | tail -n +$((KEEP+1)) | while read -r dir; do
  rel="$BASE/releases/${dir%/}"
  [ "$rel" = "$CURRENT" ] && continue
  rm -rf "$rel"
  echo "pruned ${dir%/}"
done
