#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf 'Usage: %s BACKUP_FILE --database TARGET_DATABASE [--yes]\n' "$0" >&2
}
backup_file=
target_database=
confirm=false
while (($#)); do
  case $1 in
    --database)
      (($# >= 2)) || { usage; exit 2; }
      target_database=$2
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
[[ -f $backup_file && -r $backup_file ]] || { printf 'Backup file is not readable: %s\n' "$backup_file" >&2; exit 2; }
command -v pg_dump >/dev/null 2>&1 || { printf 'pg_dump not found\n' >&2; exit 127; }
command -v pg_restore >/dev/null 2>&1 || { printf 'pg_restore not found\n' >&2; exit 127; }

BACKUP_DIR=${BACKUP_DIR:-/var/backups/fire-platform}
stamp=$(date -u '+%Y%m%dT%H%M%SZ')
planned_backup="$BACKUP_DIR/fire-platform-pre-restore-${stamp}.<unique>.dump"

printf 'Planned actions:\n  Save current database %q to %q\n  Restore %q into database %q (clean existing objects in one transaction)\n' \
  "$target_database" "$planned_backup" "$backup_file" "$target_database"
if [[ $confirm != true ]]; then
  printf 'No changes made. Re-run with --yes to execute.\n'
  exit 0
fi

mkdir -p -- "$BACKUP_DIR"
if [[ ! -d $BACKUP_DIR || -L $BACKUP_DIR ]]; then
  printf 'BACKUP_DIR must be a real directory, not a symlink\n' >&2
  exit 2
fi
tmp=$(mktemp -- "$BACKUP_DIR/fire-platform-pre-restore-${stamp}.XXXXXXXX.partial")
trap 'rm -f -- "$tmp"' EXIT
pg_dump --format=custom --file="$tmp" --dbname="$target_database"
test -s "$tmp"
pre_restore="${tmp%.partial}.dump"
ln -T -- "$tmp" "$pre_restore"
rm -- "$tmp"
trap - EXIT
printf 'Saved pre-restore backup: %s\n' "$pre_restore"
pg_restore --single-transaction --clean --if-exists --no-owner --dbname="$target_database" "$backup_file"
printf 'Restore completed. Pre-restore backup: %s\n' "$pre_restore"
