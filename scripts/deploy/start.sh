#!/usr/bin/env bash
# 启动已部署版本：current 软链指向 releases/<id>，配置在 config/env。
set -euo pipefail
BASE=${FIRE_BASE:-$HOME/fire-platform}
set -a; source "$BASE/config/env"; set +a
REL=$(readlink -f "$BASE/current")
mkdir -p "$BASE/logs" "$BASE/run"
TS=$(date +%Y%m%d-%H%M%S)
for log in app web; do [ -f "$BASE/logs/$log.log" ] && mv "$BASE/logs/$log.log" "$BASE/logs/$log-$TS.log"; done
find "$BASE/logs" -name '*.log' -mtime +14 -delete 2>/dev/null || true
setsid "$BASE/tools/jre21/bin/java" -Xms256m -Xmx1g -jar "$REL/app.jar" >"$BASE/logs/app.log" 2>&1 < /dev/null &
echo $! > "$BASE/run/app.pid"
for _ in $(seq 1 120); do curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1 && break; sleep 1; done
curl -fsS http://127.0.0.1:8080/api/health >/dev/null || { echo 'backend failed'; tail -40 "$BASE/logs/app.log"; exit 1; }
setsid node "$REL/web-proxy.mjs" "$REL/web" 127.0.0.1 8080 "${WEB_PORT:-8081}" >"$BASE/logs/web.log" 2>&1 < /dev/null &
echo $! > "$BASE/run/web.pid"
for _ in $(seq 1 60); do curl -fsS "http://127.0.0.1:${WEB_PORT:-8081}/" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:${WEB_PORT:-8081}/" >/dev/null || { echo 'web failed'; tail -20 "$BASE/logs/web.log"; exit 1; }
echo "STARTED release=$(basename "$REL") app=8080 web=${WEB_PORT:-8081}"
