"""Retire exact snapshotted host-specific entry points, retaining all original bytes as history."""
from pathlib import Path
import argparse
import hashlib
import json

parser = argparse.ArgumentParser(); parser.add_argument('--apply', action='store_true'); args = parser.parse_args()
root = Path(__file__).resolve().parents[1]; scripts = (root / 'scripts').resolve()
archive = root / 'artifacts/ubuntu-only-before'
manifest = json.loads((archive / 'manifest.json').read_text(encoding='utf8'))
records = {r['path']:r for r in manifest['files']}
sha = lambda b: hashlib.sha256(b).hexdigest().upper()
selected = []
for source in sorted(scripts.iterdir()):
    if not source.is_file(): continue
    name = source.name.lower()
    retire = source.suffix.lower() == '.ps1' or (name.startswith('p8') and source.suffix.lower() in ('.py','.md'))
    if not retire: continue
    relative = source.relative_to(root).as_posix(); old = archive / relative
    assert source.resolve().parent == scripts and relative in records and old.is_file()
    data = source.read_bytes(); assert data == old.read_bytes() and sha(data) == records[relative]['sha256'], 'Source changed after snapshot: '+relative
    selected.append({'source':relative,'resolved_source':str(source.resolve()),'archive':old.relative_to(root).as_posix(),'sha256':sha(data)})
assert selected and sum(r['source'].endswith('.ps1') for r in selected) == 15
print(json.dumps({'mode':'apply' if args.apply else 'plan','files':selected},ensure_ascii=False,indent=2))
if args.apply:
    for row in selected:
        source = (root / row['source']).resolve(); expected = scripts / Path(row['source']).name
        assert source == expected and source.parent == scripts and sha(source.read_bytes()) == row['sha256']
        assert sha((root / row['archive']).read_bytes()) == row['sha256']
        source.unlink()  # Verified exact file, never a tree or service/cache deletion.
    assert not list(scripts.glob('*.ps1'))
    proof = {'status':'host-specific-entrypoints-retired','removed':selected,'windows_powershell_entrypoints_removed':15,
             'boundary':'Explicit Ubuntu-only user request. Exact15 PS1 and old p8* host-specific proof/runtime helper or handoff entrypoints removed only after original source/byte snapshot equality. Current portable fixture-contract JSON retained. No system service, historical package/proof/image/log, business source or active Python/MJS smoke was deleted. Historical scripts are byte evidence, not current runnable support.'}
    with (root / 'artifacts/ubuntu-host-retirement-proof.json').open('x',encoding='utf8') as output: json.dump(proof,output,ensure_ascii=False,indent=2)
