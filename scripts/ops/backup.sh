#!/usr/bin/env bash
set -Eeuo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/fire-platform}
BACKUP_KEEP=${BACKUP_KEEP:-14}
DATABASE=${BACKUP_DATABASE:-${PGDATABASE:-fire_platform}}

if [[ ! $BACKUP_KEEP =~ ^[1-9][0-9]*$ ]]; then
  printf 'BACKUP_KEEP must be a positive integer\n' >&2
  exit 2
fi
if [[ -z $DATABASE ]]; then
  printf 'Set BACKUP_DATABASE or PGDATABASE\n' >&2
  exit 2
fi
if ! command -v pg_dump >/dev/null 2>&1; then
  printf 'pg_dump not found\n' >&2
  exit 127
fi
mkdir -p -- "$BACKUP_DIR"
if [[ ! -d $BACKUP_DIR || -L $BACKUP_DIR ]]; then
  printf 'BACKUP_DIR must be a real directory, not a symlink\n' >&2
  exit 2
fi

stamp=$(date -u '+%Y%m%dT%H%M%SZ')
final="$BACKUP_DIR/fire-platform-${stamp}.dump"
tmp="$final.partial.$$"
cleanup() { [[ ! -e $tmp ]] || rm -f -- "$tmp"; }
trap cleanup EXIT

pg_dump --format=custom --file="$tmp" --dbname="$DATABASE"
test -s "$tmp"
mv -- "$tmp" "$final"
trap - EXIT

# Prune only regular files matching this script's exact archive naming convention.
mapfile -t backups < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'fire-platform-????????T??????Z.dump' -printf '%f\n' | sort -r)
if ((${#backups[@]} > BACKUP_KEEP)); then
  for name in "${backups[@]:BACKUP_KEEP}"; do
    rm -- "$BACKUP_DIR/$name"
  done
fi
printf '%s\n' "$final"
