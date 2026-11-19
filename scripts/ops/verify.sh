#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
OPS_DIR="$ROOT/scripts/ops"
failed=0

emit() {
  local check=$1 status=$2 detail=$3
  printf '{"check":"%s","status":"%s","detail":"%s"}\n' "$check" "$status" "$detail"
  [[ $status == pass || $status == skip ]] || failed=1
}

for script in backup.sh restore.sh healthcheck.sh install-postgresql17.sh cutover.sh; do
  if bash -n "$OPS_DIR/$script" 2>/dev/null; then
    emit "bash_n:$script" pass "syntax valid"
  else
    emit "bash_n:$script" fail "bash -n failed"
  fi
done

if command -v shellcheck >/dev/null 2>&1; then
  if shellcheck --severity=error "$OPS_DIR/backup.sh" "$OPS_DIR/restore.sh" \
      "$OPS_DIR/healthcheck.sh" "$OPS_DIR/install-postgresql17.sh" \
      "$OPS_DIR/cutover.sh" >/dev/null 2>&1; then
    emit shellcheck pass "no error-severity findings"
  else
    emit shellcheck fail "error-severity findings"
  fi
else
  emit shellcheck skip "not installed"
fi

units=(fire-platform.service fire-platform-backup.service fire-platform-backup.timer)
if command -v systemd-analyze >/dev/null 2>&1; then
  if systemd-analyze verify "${units[@]/#/$OPS_DIR/}" >/dev/null 2>&1; then
    emit systemd_analyze_verify pass "all three unit files verified"
  else
    emit systemd_analyze_verify fail "systemd-analyze verify failed"
  fi
else
  emit systemd_analyze_verify skip "not installed; static unit assertions used"
  for unit in "${units[@]}"; do
    file="$OPS_DIR/$unit"
    if [[ ! -f $file ]]; then
      emit "unit_exists:$unit" fail "unit file missing"
      continue
    fi
    content=$(<"$file")
    if [[ $unit == *.service ]]; then
      if [[ $content =~ (^|$'\n')ExecStart=[^$'\n']+ ]]; then
        emit "exec_start:$unit" pass "ExecStart directive present"
      else
        emit "exec_start:$unit" fail "ExecStart directive missing"
      fi
    fi
    if [[ $unit == fire-platform.service ]]; then
      if [[ $content =~ (^|$'\n')Restart=on-failure($|$'\n') ]]; then
        emit restart_policy pass "Restart=on-failure"
      else
        emit restart_policy fail "Restart=on-failure missing"
      fi
    fi
    if [[ $content =~ (^|$'\n')EnvironmentFile=([^$'\n']+) ]]; then
      env_path=${BASH_REMATCH[2]}
      if [[ $env_path == /* && $env_path != -* ]]; then
        emit "environment_file:$unit" pass "absolute EnvironmentFile path declared"
      else
        emit "environment_file:$unit" fail "EnvironmentFile must be an absolute path"
      fi
    elif [[ $unit == *.service ]]; then
      emit "environment_file:$unit" fail "EnvironmentFile directive missing"
    fi
    if [[ ! $content =~ (Password|password|passwd|secret|token|api[_-]?key)[[:space:]]*=[[:space:]]*[^[:space:]#]+ ]]; then
      emit "no_plaintext_secrets:$unit" pass "no obvious inline secret assignment"
    else
      emit "no_plaintext_secrets:$unit" fail "possible inline secret assignment"
    fi
  done
fi

if ((failed)); then
  exit 1
fi
exit 0
