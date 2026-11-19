"""Capture the pre-Ubuntu-only source/support surface without changing historical evidence."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
sha = lambda b: hashlib.sha256(b).hexdigest().upper()
folder = root / 'artifacts/ubuntu-only-before'; assert not folder.exists(); folder.mkdir()
paths = []
for name in ['scripts','docs','.github','backend/src','frontend/src']:
    paths.extend(p for p in (root / name).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for name in ['README.md','IMPLEMENTATION.md','.gitignore','.env.example','backend/README.md','backend/pom.xml','frontend/README.md','frontend/package.json','frontend/package-lock.json','frontend/vite.config.ts','frontend/vitest.config.ts']:
    path = root / name
    if path.is_file(): paths.append(path)
records = []
for path in sorted(set(paths)):
    data = path.read_bytes(); relative = path.relative_to(root); target = folder / relative; target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as output: output.write(data)
    records.append({'path':relative.as_posix(),'bytes':len(data),'sha256':sha(data)})
plan_root = root.parent / '开发方案'; plan_records = []
for path in sorted(plan_root.rglob('*.md')):
    data = path.read_bytes(); relative = path.relative_to(plan_root); target = folder / 'development-plan' / relative; target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as output: output.write(data)
    plan_records.append({'path':relative.as_posix(),'bytes':len(data),'sha256':sha(data)})
assert sha((root / 'artifacts/p8e1-6B2EB082EDE12622.jar').read_bytes()) == '6B2EB082EDE12622656B69D484855B425D620F0DBA7FFDBC1671E053BAE33439'
result = {'status':'pre-ubuntu-only-snapshot','files':records,'development_plan_files':plan_records,
          'boundary':'User explicitly replaces dual-platform support with Ubuntu/Linux only. Snapshot is historical, not a current support entry. No business sources/old assertions/old immutable packages/proofs/services changed by capture. Lead owns shell/CI/docs/legacy evidence relocation; delegates have disjoint Python vs MJS write scopes.'}
with (folder / 'manifest.json').open('x',encoding='utf8') as output: json.dump(result,output,ensure_ascii=False,indent=2)
print(json.dumps({'status':result['status'],'project_files':len(records),'development_plan_files':len(plan_records)}))
