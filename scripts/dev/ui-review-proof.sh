#!/usr/bin/env bash
# 界面处置闭环：起服务 → 导入 → 投火警 → 浏览器里打开详情并提交复核 → 回读状态。
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo 'run inside WSL' >&2; exit 2; }
ROOT=${ROOT:-/home/whl/fire-platform-n05-20261030}
TOOLS=${FIRE_TOOLS_DIR:-$ROOT/.tools/linux}
export JAVA_HOME="$TOOLS/jdk-21"
export PATH="$TOOLS/node-24.16.0/bin:$TOOLS/jdk-21/bin:/usr/bin:/bin"
export FIRE_TOOLS_DIR="$TOOLS"
export BROWSER_EXECUTABLE="$TOOLS/ms-playwright/chromium-1248/chrome-linux64/chrome"
export LEGACY_APPENDIX=${LEGACY_APPENDIX:-/mnt/d/烟感/_incoming/omp-docs/appendix}
PORT=${PORT:-18083}
WORK=/tmp/ui-review
SHOTS="$WORK/shots"
rm -rf "$WORK"; mkdir -p "$SHOTS"
source "$(dirname "$0")/storage-env.sh"
BASE="http://127.0.0.1:$PORT"
JAR=$(ls "$ROOT"/backend/target/fire-platform-backend-*.jar | head -1)

APP_BOOTSTRAP_USERNAME=admin APP_BOOTSTRAP_PASSWORD='Ui-Initial-1' \
APP_PORT=$PORT APP_BIND_ADDRESS=127.0.0.1 APP_ROLES=all \
APP_LEGACY_INGEST_TOKEN=ui-token LEGACY_APPENDIX="$LEGACY_APPENDIX" \
java -jar "$JAR" >"$WORK/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; wait $APP_PID 2>/dev/null || true; stop_storage' EXIT
for _ in $(seq 1 60); do curl -fsS "$BASE/api/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "$BASE/api/health" >/dev/null || { tail -40 "$WORK/app.log"; exit 1; }

login() { curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\",\"audience\":\"management\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'; }
new_key() { python3 -c 'import uuid;print(uuid.uuid4())'; }
ADMIN=$(login admin 'Ui-Initial-1')
curl -fsS -o /dev/null -X POST "$BASE/api/auth/change-password" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"current_password":"Ui-Initial-1","new_password":"Ui-Final-1234"}'
ADMIN=$(login admin 'Ui-Final-1234')

curl -fsS -o /dev/null -X POST "$BASE/api/management/legacy-ingest/import-dictionaries" -H "Authorization: Bearer $ADMIN"
curl -fsS -o /dev/null -X POST "$BASE/api/management/legacy-ingest/import-pilot-devices" -H "Authorization: Bearer $ADMIN"

V=ui-reviewer; VINIT='UiRev-Init-1'; VPASS='UiRev-Final-1'
CODE=$(curl -sS -o "$WORK/user.json" -w '%{http_code}' -X POST "$BASE/api/management/platform-users" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(new_key)" -d "{\"username\":\"$V\",\"name\":\"界面处置\",\"initial_password\":\"$VINIT\",\"permissions\":[\"summary:read\",\"device:read\",\"alarm:read\",\"alarm:review\",\"tenants:read\"],\"tenant_ids\":[\"LEGACY-311\"],}")
echo "user create=$CODE"; [[ "$CODE" == "200" ]] || { cat "$WORK/user.json"; exit 1; }
VID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/user.json")
curl -sS -o /dev/null -X PUT "$BASE/api/management/platform-users/$VID/scope" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -H 'X-Tenant-Id: LEGACY-311' -H "Idempotency-Key: $(new_key)" -d '{"mode":"ALL_TENANT","device_ids":[],"expected_revision":0,"reason":"界面处置范围",}'
VT=$(login "$V" "$VINIT")
curl -fsS -o /dev/null -X POST "$BASE/api/auth/change-password" -H "Authorization: Bearer $VT" -H 'Content-Type: application/json' -d "{\"current_password\":\"$VINIT\",\"new_password\":\"$VPASS\"}"

curl -fsS -o /dev/null -X POST "$BASE/v1/formttl/heartbeat/telemetry" -H 'Content-Type: application/json' -H 'X-Legacy-Token: ui-token' \
 -d '{"deviceId":"869951042618672","deviceName":"869951042618672","platfromType":"tyy_turbo","productCategory":1,"productManufacturer":"Turboes","productName":"TBS-N101","deviceStateCode":"1001001","deviceState":"火警","parseMsg":[{"method":"thing.event.alarms","params":[{"smoke_value":900,"battery":"87","time":"2026-10-31 10:00:00"}]}]}'

LEGACY_BASE_URL="$BASE" LEGACY_SHOT_DIR="$SHOTS" LEGACY_USER="$V" LEGACY_PASS="$VPASS" \
LEGACY_TENANT_ID=LEGACY-311 LEGACY_TENANT_NAME='独墅湖高教区' LEGACY_ROOT="$ROOT" \
node /mnt/d/烟感/fire-platform/scripts/dev/alarm-review-shot.mjs

echo '--- 处置后状态'
curl -sS "$BASE/api/management/alarms?page=1&size=5" -H "Authorization: Bearer $ADMIN" -H 'X-Tenant-Id: LEGACY-311' -o "$WORK/alarms.json" -w 'status=%{http_code}\n'
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));a=(d.get("items") or [{}])[0];print(json.dumps({"total":d.get("total"),"response_state":a.get("response_state"),"conclusion":a.get("conclusion"),"revision":a.get("revision")},ensure_ascii=False))' "$WORK/alarms.json"
echo "shots: $SHOTS"
