#!/usr/bin/env bash
# 清空所有测试发布、数据与日志，只保留 tools 与 appendix；用于最终成品前彻底复位。
set -euo pipefail
BASE=${FIRE_BASE:-$HOME/fire-platform}
if [[ -z "$BASE" || "$BASE" == "/" || "$BASE" == "$HOME" ]]; then echo "RESET refused: unsafe FIRE_BASE" >&2; exit 2; fi
if [[ "${FIRE_RESET_CONFIRM:-}" != "DELETE_ALL" ]]; then echo "RESET refused: set FIRE_RESET_CONFIRM=DELETE_ALL to confirm destructive reset" >&2; exit 2; fi
"$BASE/stop.sh" >/dev/null 2>&1 || true
rm -rf "$BASE"/data/fire* "$BASE"/logs/* "$BASE"/run/* "$BASE"/releases/*
echo "RESET: all releases, data and logs removed (tools/appendix kept)"
