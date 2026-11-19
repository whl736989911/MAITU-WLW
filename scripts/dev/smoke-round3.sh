#!/usr/bin/env bash
# round3 真实链路冒烟：真实服务 + round3 台账导入 + 真实设备上行/火警/恢复。
# 只写本平台 H2 临时库；不连接旧平台。
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo 'run inside WSL' >&2; exit 2; }
ROOT=${ROOT:-/home/whl/fire-platform-n05-20261030}
TOOLS=${FIRE_TOOLS_DIR:-$ROOT/.tools/linux}
export JAVA_HOME="$TOOLS/jdk-21"
export PATH="$TOOLS/jdk-21/bin:$PATH"
APPENDIX=${LEGACY_APPENDIX:-/mnt/d/烟感/fire-platform/artifacts/legacy-round3/appendix}
PORT=${PORT:-18081}
WORK=/tmp/smoke-round3
rm -rf "$WORK"; mkdir -p "$WORK"

JAR=$(ls "$ROOT"/backend/target/fire-platform-backend-*.jar | head -1)
[[ -f "$JAR" ]] || { echo "jar missing; run build-jar.sh" >&2; exit 2; }

APP_BOOTSTRAP_USERNAME=admin APP_BOOTSTRAP_PASSWORD='Round3-Initial-1' \
APP_PORT=$PORT APP_BIND_ADDRESS=127.0.0.1 APP_STORAGE=h2-dev APP_ROLES=all \
APP_DB_URL="jdbc:h2:file:$WORK/fire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
APP_LEGACY_INGEST_TOKEN=round3-token LEGACY_APPENDIX="$APPENDIX" \
java -jar "$JAR" >"$WORK/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; wait $APP_PID 2>/dev/null || true' EXIT

BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 90); do curl -fsS "$BASE/api/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "$BASE/api/health" >/dev/null || { tail -40 "$WORK/app.log"; exit 1; }

login() { curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$1\",\"audience\":\"management\"}"; }
TOKEN=$(login 'Round3-Initial-1' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -fsS -o /dev/null -X POST "$BASE/api/auth/change-password" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"current_password":"Round3-Initial-1","new_password":"Round3-Final-1"}'
TOKEN=$(login 'Round3-Final-1' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
AUTH="Authorization: Bearer $TOKEN"

echo '--- import round3 dictionaries'
curl -fsS -X POST "$BASE/api/management/legacy-ingest/import-dictionaries" -H "$AUTH" | tee "$WORK/dict.json"; echo
python3 - "$WORK/dict.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert d['tenants']==36 and d['places']==284 and d['versions']==81 and d['attributes']==2590 and d['decoders']==61, d
print('dictionaries OK', d)
PY

echo '--- import all round3 devices'
curl -fsS -X POST "$BASE/api/management/legacy-ingest/import-devices" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"company_ids":["294","296","297"]}' | tee "$WORK/devices.json"; echo
python3 - "$WORK/devices.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert d['devices']==1997 and d['aliases']==3994, d
print('devices OK', d)
PY

EUI=009D9AFFFE9AE256
echo '--- imported device is visible to the operator'
TENANT=LEGACY-294
DEV=$(curl -fsS "$BASE/api/management/devices?keyword=$EUI" -H "$AUTH" -H "X-Tenant-Id: $TENANT")
echo "$DEV" | python3 -c 'import sys,json;d=json.load(sys.stdin);assert d["total"]>=1,d;print("visible",d["items"][0]["id"])'
DEVICE_ID=$(echo "$DEV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["items"][0]["id"])')

echo '--- fire alarm through the CAT1-style HTTP contract'
curl -fsS -X POST "$BASE/v1/formttl/alarm/telemetry" -H 'Content-Type: application/json' -H 'X-Legacy-Token: round3-token' \
  -d "{\"deviceId\":\"$EUI\",\"deviceName\":\"$EUI\",\"platfromType\":\"lorawan_turbo\",\"productCategory\":1,\"productManufacturer\":\"Turboes\",\"productName\":\"TBG-510\",\"deviceStateCode\":\"1001001\",\"deviceState\":\"火灾报警\",\"parseMsg\":[{\"method\":\"thing.event.alarm\",\"params\":[{\"deviceStateCode\":\"1001001\",\"deviceState\":\"火灾报警\",\"time\":\"2026-11-02 08:00:00\"}]}]}" | tee "$WORK/alarm.json"; echo

echo '--- device turns FIRE and has one unhandled alarm'
CODE=$(curl -sS -o "$WORK/detail.json" -w "%{http_code}" "$BASE/api/management/devices/$DEVICE_ID" -H "$AUTH" -H "X-Tenant-Id: $TENANT")
echo "detail HTTP $CODE"; cat "$WORK/detail.json"; echo
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["device"];assert d["physical_state"]=="FIRE",d;print("state",d["physical_state"])' "$WORK/detail.json"
ALARMS=$(curl -fsS "$BASE/api/management/devices/$DEVICE_ID/alarms" -H "$AUTH" -H "X-Tenant-Id: $TENANT")
echo "$ALARMS" | python3 -c 'import sys,json;d=json.load(sys.stdin);rows=d["items"];assert len(rows)==1,rows;assert rows[0]["response_state"]=="UNHANDLED",rows;print("alarms",len(rows))'

echo '--- recovery clears the device fire state'
curl -fsS -X POST "$BASE/v1/formttl/alarm/telemetry" -H 'Content-Type: application/json' -H 'X-Legacy-Token: round3-token' \
  -d "{\"deviceId\":\"$EUI\",\"deviceName\":\"$EUI\",\"platfromType\":\"lorawan_turbo\",\"productCategory\":1,\"productManufacturer\":\"Turboes\",\"productName\":\"TBG-510\",\"deviceStateCode\":\"1001001\",\"deviceState\":\"恢复\",\"parseMsg\":[{\"method\":\"thing.event.alarmRecovery\",\"params\":[{\"deviceStateCode\":\"1001001\",\"deviceState\":\"恢复\",\"time\":\"2026-11-02 08:06:00\"}]}]}" | tee "$WORK/recovery.json"; echo
CODE=$(curl -sS -o "$WORK/detail.json" -w "%{http_code}" "$BASE/api/management/devices/$DEVICE_ID" -H "$AUTH" -H "X-Tenant-Id: $TENANT")
echo "detail HTTP $CODE"; cat "$WORK/detail.json"; echo
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["device"];assert d["physical_state"]=="NORMAL",d;print("state",d["physical_state"])' "$WORK/detail.json"

echo 'SMOKE_ROUND3_DONE'
