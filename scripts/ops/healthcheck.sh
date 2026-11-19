#!/usr/bin/env bash
set -Eeuo pipefail

HEALTH_URL=${HEALTH_URL:-http://127.0.0.1:8080/api/health}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/fire-platform}
SERVICE_NAME=${SERVICE_NAME:-fire-platform.service}
DISK_WARN_PERCENT=${DISK_WARN_PERCENT:-85}
BACKUP_MAX_AGE_HOURS=${BACKUP_MAX_AGE_HOURS:-26}

if [[ ! $DISK_WARN_PERCENT =~ ^[0-9]+$ || ! $BACKUP_MAX_AGE_HOURS =~ ^[1-9][0-9]*$ ]]; then
  printf 'Invalid health threshold configuration\n' >&2
  exit 2
fi

http_code=000
if command -v curl >/dev/null 2>&1; then
  http_code=$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$HEALTH_URL" 2>/dev/null || printf '000')
fi
health_ok=false
if [[ $http_code =~ ^2[0-9][0-9]$ ]]; then health_ok=true; fi

disk_percent=0
disk_ok=false
if [[ -d $BACKUP_DIR ]] && command -v df >/dev/null 2>&1; then
  disk_percent=$(df -P -- "$BACKUP_DIR" | awk 'NR == 2 {gsub(/%/, "", $5); print $5}')
  if [[ $disk_percent =~ ^[0-9]+$ ]] && ((disk_percent <= DISK_WARN_PERCENT)); then disk_ok=true; fi
fi

backup_age_seconds=-1
backup_ok=false
if [[ -d $BACKUP_DIR ]]; then
  newest=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'fire-platform-????????T??????Z.dump' -printf '%T@\n' | sort -nr | head -n 1)
  if [[ $newest =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    now=$(date +%s)
    backup_age_seconds=$((now - ${newest%%.*}))
    if ((backup_age_seconds >= 0 && backup_age_seconds <= BACKUP_MAX_AGE_HOURS * 3600)); then backup_ok=true; fi
  fi
fi

process_ok=false
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "$SERVICE_NAME"; then process_ok=true; fi

printf '{"health_ok":%s,"http_status":"%s","disk_ok":%s,"disk_percent":%s,"disk_warning_percent":%s,"backup_ok":%s,"backup_age_seconds":%s,"backup_max_age_hours":%s,"process_ok":%s,"service":"%s"}\n' \
  "$health_ok" "$http_code" "$disk_ok" "${disk_percent:-null}" "$DISK_WARN_PERCENT" \
  "$backup_ok" "$backup_age_seconds" "$BACKUP_MAX_AGE_HOURS" "$process_ok" "$SERVICE_NAME"

[[ $health_ok == true && $disk_ok == true && $backup_ok == true && $process_ok == true ]]
