#!/usr/bin/env bash
# 本机测试平台：后端 8080 + 前端 5173，H2 文件库持久化，round3 台账自动导入。
# 用法: local-platform.sh {start|stop|status|logs}
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo 'run inside WSL' >&2; exit 2; }
ROOT=${ROOT:-/home/whl/fire-platform-n05-20261030}
LOCAL=${LOCAL:-/home/whl/fire-platform-local}
APPENDIX=${LEGACY_APPENDIX:-/mnt/d/烟感/fire-platform/artifacts/legacy-round3/appendix}
JAR=$(ls "$ROOT"/backend/target/fire-platform-backend-*.jar 2>/dev/null | head -1 || true)
TOOLS=${FIRE_TOOLS_DIR:-$ROOT/.tools/linux}
export JAVA_HOME="$TOOLS/jdk-21"
export PATH="$TOOLS/node-24.16.0/bin:$TOOLS/jdk-21/bin:$PATH"
INITIAL=${INITIAL:-Local-Initial-1}
FINAL=${FINAL:-Local-Admin-2026}
MIRROR_TOKEN=${MIRROR_TOKEN:-local-mirror-token-2026}
LEGACY_TOKEN=${LEGACY_TOKEN:-local-legacy-token-2026}
BASE=http://127.0.0.1:8080
UI=http://127.0.0.1:5173

pid_alive() { [[ -f "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null; }

wait_http() {
  local url=$1
  for _ in $(seq 1 120); do curl -fsS "$url" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

login_password() {
  curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$1\",\"audience\":\"management\"}" 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])' 2>/dev/null || true
}

start() {
  mkdir -p "$LOCAL/run" "$LOCAL/logs"
  [[ -f "$JAR" ]] || { echo "jar missing; run build-jar.sh first" >&2; exit 2; }
  if pid_alive "$LOCAL/run/backend.pid"; then
    echo "backend already running pid $(cat "$LOCAL/run/backend.pid")"
  else
    APP_BOOTSTRAP_USERNAME=admin APP_BOOTSTRAP_PASSWORD="$INITIAL" \
    APP_PORT=8080 APP_BIND_ADDRESS=0.0.0.0 APP_STORAGE=h2-dev APP_ROLES=all \
    APP_DB_URL="jdbc:h2:file:$LOCAL/fire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
    APP_LEGACY_INGEST_TOKEN="$LEGACY_TOKEN" FIRE_LEGACY_MIRROR_TOKEN="$MIRROR_TOKEN" \
    LEGACY_APPENDIX="$APPENDIX" \
    setsid java -jar "$JAR" >"$LOCAL/logs/backend.log" 2>&1 < /dev/null &
    echo $! >"$LOCAL/run/backend.pid"
    wait_http "$BASE/api/health" || { echo 'backend failed to start'; tail -30 "$LOCAL/logs/backend.log"; exit 1; }
  fi
  if pid_alive "$LOCAL/run/frontend.pid"; then
    echo "frontend already running pid $(cat "$LOCAL/run/frontend.pid")"
  else
    ( cd "$ROOT/frontend" && setsid "$TOOLS/node-24.16.0/bin/node" node_modules/vite/bin/vite.js --host 0.0.0.0 --port 5173 --strictPort >"$LOCAL/logs/frontend.log" 2>&1 < /dev/null & echo $! >"$LOCAL/run/frontend.pid" )
    wait_http "$UI" || { echo 'frontend failed to start'; tail -30 "$LOCAL/logs/frontend.log"; exit 1; }
  fi

  local token
  token=$(login_password "$FINAL")
  if [[ -z "$token" ]]; then
    token=$(login_password "$INITIAL")
    if [[ -z "$token" ]]; then echo 'cannot login; check logs'; exit 1; fi
    curl -fsS -o /dev/null -X POST "$BASE/api/auth/change-password" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' \
      -d "{\"current_password\":\"$INITIAL\",\"new_password\":\"$FINAL\"}"
    token=$(login_password "$FINAL")
  fi
  echo "--- importing round3 ledger (idempotent)"
  curl -fsS -X POST "$BASE/api/management/legacy-ingest/import-dictionaries" -H "Authorization: Bearer $token"; echo
  curl -fsS -X POST "$BASE/api/management/legacy-ingest/import-devices" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d '{"company_ids":["294","296","297"]}'; echo

  echo "TEST_PLATFORM_READY"
  echo "UI        : $UI  (Windows 也可用 http://localhost:5173)"
  echo "API       : $BASE"
  echo "admin     : admin / $FINAL"
  echo "mirror    : X-Ingest-Token: $MIRROR_TOKEN"
  echo "  TTL     : $BASE/ingest/ttl<原始URI>"
  echo "  CAT1    : $BASE/ingest/cat1<原始URI>"
  echo "legacy tok: $LEGACY_TOKEN"
}

stop() {
  for name in frontend backend; do
    if pid_alive "$LOCAL/run/$name.pid"; then
      kill "$(cat "$LOCAL/run/$name.pid")" 2>/dev/null || true
      rm -f "$LOCAL/run/$name.pid"
      echo "stopped $name"
    fi
  done
}

status() {
  for name in backend frontend; do
    if pid_alive "$LOCAL/run/$name.pid"; then echo "$name: running pid $(cat "$LOCAL/run/$name.pid")"; else echo "$name: stopped"; fi
  done
  curl -fsS "$BASE/api/health" >/dev/null 2>&1 && echo "health: ok" || echo "health: down"
  curl -fsS "$UI" >/dev/null 2>&1 && echo "ui: ok" || echo "ui: down"
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  logs) tail -n 80 "$LOCAL/logs/backend.log" "$LOCAL/logs/frontend.log" 2>/dev/null ;;
  *) echo "usage: $0 {start|stop|status|logs}" >&2; exit 2 ;;
esac
