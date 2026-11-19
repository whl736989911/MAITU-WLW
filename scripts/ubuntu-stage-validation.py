"""Create a Linux-native validation copy, never reuse host generated binaries/dependencies."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import shutil

parser=argparse.ArgumentParser(); parser.add_argument('--source',required=True); parser.add_argument('--target',required=True); args=parser.parse_args()
assert platform.system()=='Linux'
source=Path(args.source).resolve(); target=Path(args.target).resolve()
assert (source/'backend/pom.xml').is_file() and source != target and not target.is_relative_to(source)
assert target.is_absolute() and target.parent == Path.home() and target.name.startswith('fire-platform-ubuntu-')
target.mkdir(exist_ok=True)
assert {p.name for p in target.iterdir()} <= {'.tools'}, 'Fresh validation source only; never overwrite a running/stale workspace'
sha=lambda b:hashlib.sha256(b).hexdigest().upper()
paths=[]
for relative in ['backend/src','frontend/src','frontend/public','scripts','.github']:
    folder=source/relative
    if folder.exists(): paths.extend(p for p in folder.rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for relative in ['backend/pom.xml','backend/README.md','frontend/README.md','frontend/P8e1-handoff.md','frontend/P8d1-handoff.md','frontend/index.html','frontend/package.json','frontend/package-lock.json','frontend/vite.config.ts','frontend/tsconfig.json','frontend/tsconfig.app.json','frontend/tsconfig.node.json','frontend/components.json','README.md','IMPLEMENTATION.md','.gitignore','.gitattributes','.env.example']:
    path=source/relative
    if path.is_file(): paths.append(path)
records=[]
for path in sorted(set(paths)):
    relative=path.relative_to(source); before=path.read_bytes(); destination=target/relative; destination.parent.mkdir(parents=True,exist_ok=True)
    with destination.open('xb') as output:output.write(before)
    assert path.read_bytes()==before and destination.read_bytes()==before
    records.append({'path':relative.as_posix(),'bytes':len(before),'sha256':sha(before)})
result={'status':'linux-source-copy-byte-verified','source':str(source),'target':str(target),'files':records,
        'boundary':'Only source/config/support copied. Host node_modules/target/dist/runtime/tools were not copied; Linux dependencies/binaries are separately prepared. Documentary source-copy hashes describe this capture, not later delivery-doc edits.'}
folder=target/'artifacts/ubuntu-linux';folder.mkdir(parents=True)
with (folder/'source-copy-manifest.json').open('x',encoding='utf8') as output:json.dump(result,output,ensure_ascii=False,indent=2)
print(json.dumps({'status':result['status'],'files':len(records),'target':str(target)}))
