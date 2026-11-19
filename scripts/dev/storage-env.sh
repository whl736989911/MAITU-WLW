#!/usr/bin/env bash
# 存储适配：STORAGE=h2-dev|postgresql 时导出 APP_STORAGE/APP_DB_URL/APP_DB_USERNAME/APP_DB_PASSWORD。
# 由 dev 冒烟脚本 source；调用方需先设置 WORK、TOOLS。
STORAGE=${STORAGE:-h2-dev}
export APP_STORAGE="$STORAGE"
if [[ "$STORAGE" == postgresql ]]; then
  PGBIN="$TOOLS/postgresql-17.11/bin"
  PGPORT=${PGPORT:-55432}
  PGDATA_DIR="$WORK/pgdata"
  PGSOCK="$WORK/pgsock"
  mkdir -p "$PGSOCK"
  if [[ ! -d "$PGDATA_DIR" ]]; then
    "$PGBIN/initdb" -D "$PGDATA_DIR" -U postgres -A trust --no-locale -E UTF8 >"$WORK/initdb.log" 2>&1
    # 该发行版只带 initdb/pg_ctl/postgres：单用户模式建库，失败必须显式暴露
    printf 'CREATE DATABASE fire;\n' | "$PGBIN/postgres" --single -D "$PGDATA_DIR" postgres >"$WORK/createdb.log" 2>&1 \
      || { tail -20 "$WORK/createdb.log"; return 1 2>/dev/null || exit 1; }
  fi
  "$PGBIN/pg_ctl" -D "$PGDATA_DIR" -o "-p $PGPORT -k $PGSOCK -h 127.0.0.1" -l "$WORK/pg.log" -w start >/dev/null \
    || { tail -20 "$WORK/pg.log"; return 1 2>/dev/null || exit 1; }
  export APP_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/fire"
  export APP_DB_USERNAME=postgres
  export APP_DB_PASSWORD=
  stop_storage() { "$PGBIN/pg_ctl" -D "$PGDATA_DIR" -m immediate -w stop >/dev/null 2>&1 || true; }
else
  export APP_DB_URL="jdbc:h2:file:$WORK/fire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
  export APP_DB_USERNAME=sa
  export APP_DB_PASSWORD=
  stop_storage() { :; }
fi
