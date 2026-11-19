"""Read-only census of N05-owned processes, PostgreSQL markers and short temporary directories."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import json
import os
import platform
import re
import stat

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--label', required=True)
a = p.parse_args()
ROOT = Path('/home/whl/fire-platform-n05-20261030')
assert platform.system() == 'Linux' and os.geteuid() == 1000 and re.fullmatch(r'[a-z0-9-]+', a.label)
assert (ROOT / 'artifacts/n05/workspace-before.json').is_file()
processes = []
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit() or int(proc.name) == os.getpid():
        continue
    try:
        if proc.stat().st_uid != os.geteuid():
            continue
        cwd = (proc / 'cwd').resolve()
        command = (proc / 'cmdline').read_bytes()
        if str(ROOT).encode() in command or cwd == ROOT or ROOT in cwd.parents:
            processes.append({'pid': int(proc.name), 'comm': (proc / 'comm').read_text().strip(), 'cwd_in_root': cwd == ROOT or ROOT in cwd.parents, 'argv_mentions_root': str(ROOT).encode() in command})
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        pass
markers = []
for name in ['linux-short-tmp.json', 'node-short-tmp.json']:
    for path in sorted((ROOT / '.runtime').rglob(name)):
        info = path.lstat()
        assert stat.S_ISREG(info.st_mode) and not path.is_symlink() and info.st_uid == os.geteuid()
        value = json.loads(path.read_bytes())
        directory = value['directory']
        assert re.fullmatch(r'/tmp/(?:ff-[A-Za-z0-9_-]+|fire-node-[A-Za-z0-9]{6})', directory)
        source = Path(value.get('source_tmp', value.get('source_run', '')))
        assert source.is_absolute() and source.is_relative_to(ROOT) and value['uid'] == os.geteuid() and value['mode'] == 0o700
        markers.append({'marker': path.relative_to(ROOT).as_posix(), 'directory': directory, 'present': os.path.lexists(directory), 'owner_pid': value['owner_pid']})
pg = sorted(path.relative_to(ROOT).as_posix() for path in (ROOT / '.runtime').rglob('postmaster.pid'))
clear = not processes and not pg and not any(row['present'] for row in markers)
value = {'status': 'owned-resource-census-clear' if clear else 'owned-resource-census-unconfirmed', 'observed_at': datetime.now(timezone.utc).isoformat(), 'owned_root_processes': processes, 'postmaster_pid_paths': pg, 'short_temporary_markers': markers, 'short_temporary_total': len(markers), 'short_temporary_present': sum(row['present'] for row in markers), 'boundary': 'Independent read-only scan of uid-1000 processes mentioning or residing in the N05 root, N05 PostgreSQL PID paths, and validated uid-700 short temporary markers; no process kill, service mutation or deletion.'}
out = ROOT / ('artifacts/n05/census-' + a.label + '.json')
with out.open('x', encoding='utf8') as stream:
    json.dump(value, stream, indent=2)
print(json.dumps({'status': value['status'], 'owned_processes': len(processes), 'pg_markers': len(pg), 'temporary_total': len(markers), 'temporary_present': value['short_temporary_present']}))
assert clear
