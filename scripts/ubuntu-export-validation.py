"""Copy only finalized public validation artifacts, byte-exact; never copy private runtime/PG data."""
from pathlib import Path
import argparse
import hashlib
import json
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--destination',required=True);args=parser.parse_args()
root=Path(args.root).resolve();source=root/'artifacts/ubuntu-linux';destination=Path(args.destination).resolve();assert source!=destination
proof=json.loads((source/'final-verification.json').read_text(encoding='utf8'));assert proof['status']=='passed' and proof['unique_runtime_stages']==40
destination.mkdir(parents=True,exist_ok=True);sha=lambda b:hashlib.sha256(b).hexdigest().upper();rows=[]
for path in sorted(source.rglob('*')):
    assert not path.is_symlink()
    if not path.is_file():continue
    relative=path.relative_to(source);target=destination/relative;raw=path.read_bytes();target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():assert target.is_file() and target.read_bytes()==raw, 'Refusing unrelated/changed artifact overwrite: '+str(target)
    else:
        with target.open('xb') as stream:stream.write(raw)
    assert target.read_bytes()==raw and path.read_bytes()==raw
    rows.append({'path':relative.as_posix(),'sha256':sha(raw),'bytes':len(raw)})
manifest={'status':'verified-byte-exact-export','artifact_sha256':proof['artifact_sha256'],'files':rows,'boundary':'Only artifacts/ubuntu-linux, not .runtime/database/config/home or tools; existing host-only source-audit/navigation/review records are retained separately, no deletes/overwrites.'}
with (destination/'export-manifest.json').open('x',encoding='utf8') as stream:json.dump(manifest,stream,ensure_ascii=False,indent=2)
print(json.dumps({'status':manifest['status'],'files':len(rows),'destination':str(destination)}))
