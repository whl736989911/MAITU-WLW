#!/usr/bin/env bash
set +e
BASE=${FIRE_BASE:-$HOME/fire-platform}
for name in web app; do
  [ -f "$BASE/run/$name.pid" ] && kill "$(cat "$BASE/run/$name.pid")" 2>/dev/null
  rm -f "$BASE/run/$name.pid"
done
fuser -k 8080/tcp 8081/tcp 2>/dev/null
echo STOPPED
