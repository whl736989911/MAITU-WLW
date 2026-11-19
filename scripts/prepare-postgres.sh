#!/usr/bin/env bash
# Offline-only installer of Root's checksum-pinned Linux PostgreSQL17 fixture.
set -euo pipefail
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'HELP'
Usage: bash scripts/prepare-postgres.sh LOCAL_ARCHIVE SHA256
   or: PG_ARCHIVE=/absolute/local.jar PG_ARCHIVE_SHA256=<64-hex> bash scripts/prepare-postgres.sh
Both the pre-downloaded archive and SHA256 are REQUIRED.
The archive must ALREADY EXIST; this script never downloads it.
SHA256 must equal this fixed trusted pin:
0dd7b72b6f335b8ecfb355fa24c5781e8a93edd09880bb77eb52ebbf29b3e96d
Archive: embedded-postgres-binaries-linux-amd64-17.11.0.jar
Pinned upstream identity (download is explicitly the operator's responsibility):
https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/17.11.0/embedded-postgres-binaries-linux-amd64-17.11.0.jar
Installs only to ${FIRE_TOOLS_DIR:-<project>/.tools/linux}/postgresql-17.11.
Requires Ubuntu/Linux x86_64, non-root, Python>=3.12. No network, apt, service,
cluster, JVM or browser is started. Existing destination is NEVER overwritten.
For an installed Ubuntu PG17 use PG_BIN instead; LIBPQ_PATH can select libpq.
HELP
  exit 0
fi
[[ $# -le 2 ]] || { printf '%s\n' 'Expected local archive and SHA256 only' >&2; exit 2; }
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { printf '%s\n' 'Only Ubuntu/Linux x86_64 is supported by this pinned binary archive' >&2; exit 2; }
[[ "$(id -u)" != 0 ]] || { printf '%s\n' 'Run fixture preparation as a non-root Ubuntu user' >&2; exit 2; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
export FIRE_TOOLS_DIR="${FIRE_TOOLS_DIR:-$root/.tools/linux}"
archive="${1:-${PG_ARCHIVE:-}}"
checksum="${2:-${PG_ARCHIVE_SHA256:-}}"
[[ -n "$archive" && "$checksum" =~ ^[a-fA-F0-9]{64}$ ]] || { printf '%s\n' 'Required: existing local 17.11.0 Linux archive and valid pinned SHA256 (see --help)' >&2; exit 2; }
export PG_ARCHIVE="$archive" PG_ARCHIVE_SHA256="$checksum"
python3 - <<'PY'
import ctypes
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import posixpath
import shutil
import struct
import sys
import tarfile
import tempfile
from zipfile import ZipFile

if sys.platform != 'linux' or sys.version_info < (3, 12) or os.geteuid() == 0:
    raise SystemExit('Requires non-root Ubuntu/Linux and Python>=3.12 data-filter extraction')
archive = Path(os.environ['PG_ARCHIVE']).expanduser().resolve(strict=True)
expected = os.environ['PG_ARCHIVE_SHA256'].upper()
if archive.name != 'embedded-postgres-binaries-linux-amd64-17.11.0.jar' or not archive.is_file() or archive.stat().st_size > 200_000_000:
    raise SystemExit('Expected the pinned 17.11.0 linux-amd64 archive, at most 200MB')
pinned = '0DD7B72B6F335B8ECFB355FA24C5781E8A93EDD09880BB77EB52EBBF29B3E96D'
if expected != pinned:
    raise SystemExit('Supplied checksum is not the pinned 17.11.0 Linux artifact')
with archive.open('rb') as source:
    digest = hashlib.file_digest(source, 'sha256').hexdigest().upper()
if digest != expected:
    raise SystemExit('PostgreSQL archive SHA256 mismatch; source/cache not modified')
tools = Path(os.environ['FIRE_TOOLS_DIR']).expanduser()
if not tools.is_absolute():
    raise SystemExit('FIRE_TOOLS_DIR must be an absolute Linux path')
tools = tools.resolve()
tools.mkdir(parents=True, exist_ok=True)
output = tools / 'postgresql-17.11'
if output.exists() or output.is_symlink():
    raise SystemExit('PostgreSQL destination already exists; refusing replacement')
stage = Path(tempfile.mkdtemp(prefix='.postgresql-17.11-stage-', dir=tools))
try:
    with ZipFile(archive) as zipped:
        entries = [entry for entry in zipped.infolist() if entry.filename == 'postgres-linux-x86_64.txz']
        if len(entries) != 1 or entries[0].file_size > 200_000_000:
            raise SystemExit('Expected one bounded Linux PostgreSQL payload')
        with tarfile.open(fileobj=io.BytesIO(zipped.read(entries[0])), mode='r:xz') as packed:
            members = packed.getmembers()
            if len(members) > 20_000 or sum(entry.size for entry in members) > 600_000_000:
                raise SystemExit('PostgreSQL payload exceeds extraction limits')
            seen = set()
            for entry in members:
                path = PurePosixPath(entry.name)
                if path.is_absolute() or '..' in path.parts or '\\' in entry.name or '\0' in entry.name:
                    raise SystemExit('Unsafe payload member path')
                key = path.as_posix()
                if key in seen or not (entry.isfile() or entry.isdir() or entry.issym() or entry.islnk()):
                    raise SystemExit('Duplicate or unsupported payload member')
                seen.add(key)
                if entry.issym() or entry.islnk():
                    link = PurePosixPath(entry.linkname)
                    target = posixpath.normpath(str(path.parent / link) if entry.issym() else str(link))
                    if link.is_absolute() or target == '..' or target.startswith('../') or '\\' in entry.linkname:
                        raise SystemExit('Payload link escapes its private root')
            # Python's data filter additionally resolves symlinks against the
            # current extraction tree, rejects special files/escapes and strips
            # unsafe ownership/mode data. Fresh stage is private mode0700.
            packed.extractall(stage, members=members, filter='data')
    for tool in ('postgres', 'initdb', 'pg_ctl'):
        path = (stage / 'bin' / tool).resolve(strict=True)
        if not path.is_relative_to(stage) or not path.is_file():
            raise SystemExit('Expected contained PostgreSQL executables')
        with path.open('rb') as source:
            header = source.read(20)
        if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != 62 or not os.access(path, os.X_OK):
            raise SystemExit('PostgreSQL tool must be an executable Linux x86_64 ELF')
    if output.exists() or output.is_symlink():
        raise SystemExit('Destination appeared during extraction; refusing replacement')
    # Atomic publish into an absent destination on the same filesystem; no
    # archive cleanup or global filesystem/service modification is performed.
    libc = ctypes.CDLL(None, use_errno=True)
    publish = libc.renameat2
    publish.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    publish.restype = ctypes.c_int
    if publish(-100, os.fsencode(stage), -100, os.fsencode(output), 1) != 0:  # RENAME_NOREPLACE
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))
    print('Verified local PostgreSQL17.11 Linux fixture installed at ' + str(output))
finally:
    if stage.exists():
        if stage.parent != tools or not stage.name.startswith('.postgresql-17.11-stage-') or stage.is_symlink():
            raise RuntimeError('Private staging identity changed; refusing cleanup')
        shutil.rmtree(stage)
PY
