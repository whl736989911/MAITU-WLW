#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf 'Usage: %s BACKUP_FILE --host HOST_OR_SOCKET --port PORT --database TARGET_DATABASE [--yes]\n' "$0" >&2
}
backup_file=
target_host=
target_port=
target_database=
confirm=false
while (($#)); do
  case $1 in
    --host|--port|--database)
      (($# >= 2)) || { usage; exit 2; }
      case $1 in
        --host) target_host=$2 ;;
        --port) target_port=$2 ;;
        --database) target_database=$2 ;;
      esac
      shift 2
      ;;
    --yes)
      confirm=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    -* )
      usage
      exit 2
      ;;
    *)
      [[ -z $backup_file ]] || { usage; exit 2; }
      backup_file=$1
      shift
      ;;
  esac
done
[[ -n $backup_file && -n $target_database ]] || { usage; exit 2; }
if [[ -z $target_host || $target_host == *,* || ! $target_port =~ ^[1-9][0-9]{0,4}$ ]] ||
   ((target_port > 65535)); then
  printf 'Supply one explicit --host and a --port between 1 and 65535\n' >&2
  exit 2
fi
if [[ $target_database == *=* || $target_database == postgres://* || $target_database == postgresql://* ]]; then
  printf '%s\n' '--database must be a database name, not a connection string or URI' >&2
  exit 2
fi
[[ -f $backup_file && -r $backup_file ]] || { printf 'Backup file is not readable: %s\n' "$backup_file" >&2; exit 2; }
command -v pg_dump >/dev/null 2>&1 || { printf 'pg_dump not found\n' >&2; exit 127; }
command -v pg_restore >/dev/null 2>&1 || { printf 'pg_restore not found\n' >&2; exit 127; }

BACKUP_DIR=${BACKUP_DIR:-/var/backups/fire-platform}
stamp=$(date -u '+%Y%m%dT%H%M%SZ')
planned_backup="$BACKUP_DIR/fire-platform-pre-restore-${stamp}.<unique>.dump"

printf 'Planned actions for host/socket %q, port %s, database %q:\n  Save current database to %q\n  Restore %q (clean archived objects in one transaction)\n' \
  "$target_host" "$target_port" "$target_database" "$planned_backup" "$backup_file"
if [[ $confirm != true ]]; then
  printf 'No changes made. Re-run with --yes to execute.\n'
  exit 0
fi

# libpq can otherwise use a different network address than the explicit --host.
unset PGHOSTADDR PGSERVICE
connection=(--host="$target_host" --port="$target_port" --dbname="$target_database")

mkdir -p -- "$BACKUP_DIR"
if [[ ! -d $BACKUP_DIR || -L $BACKUP_DIR ]]; then
  printf 'BACKUP_DIR must be a real directory, not a symlink\n' >&2
  exit 2
fi
tmp=$(mktemp -- "$BACKUP_DIR/fire-platform-pre-restore-${stamp}.XXXXXXXX.partial")
trap 'rm -f -- "$tmp"' EXIT
pg_dump --format=custom --file="$tmp" "${connection[@]}"
test -s "$tmp"
pre_restore="${tmp%.partial}.dump"
ln -T -- "$tmp" "$pre_restore"
rm -- "$tmp"
trap - EXIT
printf 'Saved pre-restore backup: %s\n' "$pre_restore"
pg_restore --single-transaction --clean --if-exists --no-owner "${connection[@]}" "$backup_file"
printf 'Restore completed. Pre-restore backup: %s\n' "$pre_restore"
