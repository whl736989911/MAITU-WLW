#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPO_URL='https://apt.postgresql.org/pub/repos/apt'
readonly REPO_SUITE_SUFFIX='-pgdg'
readonly KEY_URL='https://www.postgresql.org/media/keys/ACCC4CF8.asc'
readonly EXPECTED_FINGERPRINT='B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8'
readonly KEY_FILE='/usr/share/keyrings/pgdg.asc'
readonly SOURCE_FILE='/etc/apt/sources.list.d/pgdg.list'
readonly SERVER_PACKAGE='postgresql-17'
readonly CLIENT_PACKAGE='postgresql-client-17'
apply=false

usage() {
  cat <<EOF
Usage: $0 [--apply] [--help]

Default: validate the host and print a read-only installation plan.
--apply: install PostgreSQL 17 server and full client from the official PGDG
         apt repository (https://apt.postgresql.org/pub/repos/apt).
         The key is fetched from ${KEY_URL} and its primary fingerprint must
         equal ${EXPECTED_FINGERPRINT} before it is trusted.

Apply with: sudo $0 --apply
Review afterward: pg_lsclusters; /usr/lib/postgresql/17/bin/psql --version;
                  /usr/lib/postgresql/17/bin/pg_dump --version;
                  /usr/lib/postgresql/17/bin/pg_restore --version
EOF
}

while (($#)); do
  case $1 in
    --apply) apply=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { printf 'PostgreSQL 17 installer: %s\n' "$*" >&2; exit 1; }

[[ $(uname -s) == Linux ]] || fail 'Linux is required.'
[[ -r /etc/os-release ]] || fail '/etc/os-release is unavailable; cannot verify Ubuntu release.'
# shellcheck disable=SC1091
. /etc/os-release
[[ ${ID:-} == ubuntu ]] || fail "Ubuntu is required (detected ${ID:-unknown})."
release=${VERSION_ID:-}

case $release in
  22.04|24.04|26.04) ;;
  *) fail "Unsupported Ubuntu release ${release:-unknown}; supported releases are 22.04, 24.04 and 26.04." ;;
esac
command -v dpkg >/dev/null 2>&1 || fail 'dpkg is required to verify the amd64 architecture.'
[[ $(dpkg --print-architecture) == amd64 ]] || fail "amd64 is required (detected $(dpkg --print-architecture))."

suite="${VERSION_CODENAME:-}"
[[ -n $suite ]] || fail 'Ubuntu VERSION_CODENAME is missing.'
repo_line="deb [arch=amd64 signed-by=${KEY_FILE}] ${REPO_URL} ${suite}${REPO_SUITE_SUFFIX} main"

primary_fingerprint() {
  gpg --batch --show-keys --with-colons --fingerprint "$1" 2>/dev/null |
    awk -F: '$1 == "pub" { primaries++; need=1; next } need && $1 == "fpr" { print toupper($10); need=0 } END { if (primaries != 1) exit 1 }'
}

# Never replace a pre-existing trust or repository configuration. An exact
# source line is reused; an existing key is accepted only after fingerprint verification.
source_state=absent
if [[ -e $SOURCE_FILE || -L $SOURCE_FILE ]]; then
  [[ -f $SOURCE_FILE && ! -L $SOURCE_FILE ]] || fail "Existing repository path is not a regular file: ${SOURCE_FILE}"
  [[ $(cat -- "$SOURCE_FILE") == "$repo_line" ]] || fail "Unexpected existing repository file; refusing to modify ${SOURCE_FILE}."
  source_state=present
fi
key_state=absent
if [[ -e $KEY_FILE || -L $KEY_FILE ]]; then
  [[ -f $KEY_FILE && ! -L $KEY_FILE ]] || fail "Existing signing-key path is not a regular file: ${KEY_FILE}"
  command -v gpg >/dev/null 2>&1 || fail 'gpg is required to verify the existing PGDG key.'
  existing_fingerprint=$(primary_fingerprint "$KEY_FILE") || fail "Could not verify one primary fingerprint in ${KEY_FILE}."
  [[ $existing_fingerprint == "$EXPECTED_FINGERPRINT" ]] || fail "Existing key file does not have the approved PGDG primary fingerprint; refusing to modify ${KEY_FILE}."
  key_state=verified
fi

printf 'Host: Ubuntu %s (%s), amd64\n' "$release" "$suite"
printf 'Repository: %s, suite %s%s, signed by primary fingerprint %s\n' "$REPO_URL" "$suite" "$REPO_SUITE_SUFFIX" "$EXPECTED_FINGERPRINT"
printf 'Trust key: %s (%s)\n' "$KEY_FILE" "$key_state"
printf 'Repository file: %s (%s)\n' "$SOURCE_FILE" "$source_state"
printf 'Packages: %s %s\n' "$SERVER_PACKAGE" "$CLIENT_PACKAGE"

if [[ $apply != true ]]; then
  printf 'Plan only; no changes made. Re-run with --apply to install.\n'
  exit 0
fi
[[ $(id -u) == 0 ]] || fail '--apply must be run as root.'
for utility in apt-get curl gpg awk install; do
  command -v "$utility" >/dev/null 2>&1 || fail "Required command not found: ${utility}"
done

workdir=$(mktemp -d)
cleanup() { rm -rf -- "$workdir"; }
trap cleanup EXIT

if [[ $key_state == absent ]]; then
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --output "$workdir/pgdg.asc" "$KEY_URL"
  fingerprint=$(primary_fingerprint "$workdir/pgdg.asc") || fail 'Downloaded key did not contain exactly one verifiable primary key.'
  [[ $fingerprint == "$EXPECTED_FINGERPRINT" ]] || fail 'Downloaded signing key primary fingerprint did not match the approved PostgreSQL PGDG fingerprint.'
  install -d -m 0755 /usr/share/keyrings
  # Avoid clobbering a file created after the initial inspection.
  (set -o noclobber; cat -- "$workdir/pgdg.asc" > "$KEY_FILE") || fail "Signing-key path appeared during installation; refusing to overwrite ${KEY_FILE}."
  chmod 0644 "$KEY_FILE"
  key_state=verified
fi

if [[ $source_state == absent ]]; then
  # Create exclusively; never replace a file that appeared since inspection.
  (set -o noclobber; printf '%s\n' "$repo_line" > "$SOURCE_FILE") || fail "Repository path appeared during installation; refusing to overwrite ${SOURCE_FILE}."
  chmod 0644 "$SOURCE_FILE"
fi

export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get update
apt-get install --yes "$SERVER_PACKAGE" "$CLIENT_PACKAGE"

server_bin='/usr/lib/postgresql/17/bin'
for binary in psql pg_dump pg_restore; do
  path="${server_bin}/${binary}"
  [[ -x $path ]] || fail "Package installation did not provide executable ${path}."
  version=$("$path" --version)
  [[ $version == *'(PostgreSQL) 17.'* ]] || fail "Unexpected version from ${path}: ${version}"
  printf 'Ready: %s (%s)\n' "$path" "$version"
done
if command -v dpkg-query >/dev/null 2>&1; then
  for package in "$SERVER_PACKAGE" "$CLIENT_PACKAGE"; do
    status=$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)
    [[ $status == 'install ok installed' ]] || fail "Package is not fully installed: ${package}"
  done
fi
if command -v pg_lsclusters >/dev/null 2>&1; then
  printf 'Cluster inventory (pg_lsclusters):\n'
  clusters=$(pg_lsclusters --no-header)
  if [[ -n $clusters ]]; then
    printf '%s\n' "$clusters"
  else
    printf 'No PostgreSQL clusters are registered.\n'
  fi
else
  printf 'Cluster inventory unavailable: pg_lsclusters is not installed.\n'
fi
if command -v pg_conftool >/dev/null 2>&1 && [[ -f /etc/postgresql/17/main/postgresql.conf ]]; then
  if configured_listen=$(pg_conftool 17 main show listen_addresses); then
    printf 'PostgreSQL 17/main explicit listen_addresses: %s\n' "$configured_listen"
  else
    printf 'listen_addresses is not explicit in the main config; inspect the running server with SHOW listen_addresses before cutover.\n'
  fi
fi
printf 'PostgreSQL 17 server and full client packages are installed. Package defaults may create/start the standard 17/main cluster; no application configuration or database was changed.\n'
