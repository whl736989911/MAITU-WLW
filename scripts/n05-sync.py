"""Copy an explicit source scope into the fresh N05 Linux root only with no active readers."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import platform

p = argparse.ArgumentParser()
p.add_argument('--scope', choices=['backend', 'frontend', 'all'], required=True)
p.add_argument('--label', required=True)
p.add_argument('--allow-removals', action='store_true', help='显式允许删除已同步到远端的源（如重构移动），并记录 removed 列表')
a = p.parse_args()
assert platform.system() == 'Linux' and os.geteuid() == 1000 and a.label.replace('-', '').isalnum()
s = Path('/mnt/d/烟感/fire-platform')
r = Path('/home/whl/fire-platform-n05-20261030')
assert (r / 'artifacts/n05/workspace-before.json').is_file()
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit() or int(proc.name) == os.getpid():
        continue
    try:
        if proc.stat().st_uid != os.geteuid():
            continue
        cwd, command = (proc / 'cwd').resolve(), (proc / 'cmdline').read_bytes()
        executable = (proc / 'exe').resolve().name
        if executable in {'java', 'node', 'postgres', 'chrome', 'chrome_crashpad_handler'}:
            reads_project = any(str(r / part).encode() in command for part in ('backend', 'frontend', 'scripts'))
            assert not (reads_project or cwd == r or r in cwd.parents), 'Active N05 runtime/build reader'
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        pass
managed, paths, removed = [], set(), []
if a.scope in {'backend', 'all'}:
    managed.append('backend/src')
    paths.update(s / name for name in ['backend/pom.xml', 'backend/README.md'])
if a.scope in {'frontend', 'all'}:
    managed.append('frontend/src')
    paths.update(path for path in (s / 'frontend').iterdir() if path.is_file() and path.suffix in {'.json', '.ts', '.js', '.html', '.css', '.md'})
if a.scope == 'all':
    managed += ['scripts', '.github', 'deploy']
    paths.update(s / name for name in ['README.md', 'IMPLEMENTATION.md', '.env.example', '.gitignore', '.gitattributes'])
    paths.update((s / 'docs').glob('*.md'))
    paths.update(s / name for name in ['artifacts/n04/consumer-test-cutover.json', 'artifacts/n04/legacy-runtime-adaptations.json', 'artifacts/n05/version-caller-derivations.json'])
for directory in managed:
    paths.update(path for path in (s / directory).rglob('*') if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    for path in (r / directory).rglob('*'):
        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
            if (s / path.relative_to(r)).is_file():
                continue
            assert a.allow_removals, 'Unexpected removed source: ' + str(path)
            removed.append(path.relative_to(r).as_posix())
            path.unlink()
sha = lambda data: hashlib.sha256(data).hexdigest().upper()
rows = []
for path in sorted(paths):
    assert path.is_file() and not path.is_symlink()
    name, data = path.relative_to(s), path.read_bytes()
    target = r / name
    assert not target.is_symlink()
    changed = not target.is_file() or target.read_bytes() != data
    target.parent.mkdir(parents=True, exist_ok=True)
    if changed:
        target.write_bytes(data)
    assert target.read_bytes() == data
    rows.append({'path': name.as_posix(), 'sha256': sha(data), 'bytes': len(data), 'changed': changed})
for item in rows:
    assert sha((s / item['path']).read_bytes()) == item['sha256'] == sha((r / item['path']).read_bytes()), 'Writer changed during source freeze'
value = {'status': 'exact-source-sync', 'scope': a.scope, 'label': a.label, 'observed_at': datetime.now(timezone.utc).isoformat(), 'files': rows}
raw = json.dumps(value, ensure_ascii=False, indent=2).encode('utf8')
out = r / ('artifacts/n05/sync-' + a.label + '.json')
with out.open('xb') as stream:
    stream.write(raw)
print(json.dumps({'status': value['status'], 'files': len(rows), 'changed': sum(item['changed'] for item in rows), 'removed': removed, 'snapshot_sha256': sha(raw)}))
