"""Remove only the superseded dual-system plan after verifying its original archived bytes."""
from pathlib import Path
import argparse
import hashlib
import json

parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[1]; plan=(root.parent/'开发方案').resolve()
relative='详细设计/18-低配双系统与分机部署实施约束.md'
old=(plan/relative).resolve();new=plan/'详细设计/18-低配Ubuntu与分机部署实施约束.md'
archive=root/'artifacts/ubuntu-only-before/development-plan'/relative
manifest=json.loads((root/'artifacts/ubuntu-only-before/manifest.json').read_text(encoding='utf8'))
record=next(r for r in manifest['development_plan_files'] if r['path']==relative)
assert old.parent==(plan/'详细设计') and old.name=='18-低配双系统与分机部署实施约束.md' and new.is_file()
raw=old.read_bytes();assert raw==archive.read_bytes() and hashlib.sha256(raw).hexdigest().upper()==record['sha256']
print(json.dumps({'resolved_exact_source':str(old),'original_archive':str(archive),'replacement':str(new),'sha256':record['sha256'],'mode':'apply' if args.apply else 'plan'},ensure_ascii=False))
if args.apply:old.unlink()
