"""Read-only census of this exact fresh Ubuntu workspace; never kills/deletes a resource."""
from pathlib import Path
import argparse
from datetime import datetime, timezone
import json
import os
import platform
import re
import stat

parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--require-idle',action='store_true');parser.add_argument('--output');args=parser.parse_args()
assert platform.system()=='Linux' and os.getuid()!=0
root=Path(args.root).resolve();runtime=root/'.runtime';tools=root/'.tools/linux';uid=os.getuid();owned=[]
for item in Path('/proc').iterdir():
    if not item.name.isdigit() or int(item.name)==os.getpid():continue
    try:
        if item.stat().st_uid!=uid:continue
        executable=(item/'exe').resolve(strict=True);cwd=(item/'cwd').resolve(strict=True)
        argv=(item/'cmdline').read_bytes().split(b'\0');argv=[part.decode('utf8','replace') for part in argv if part]
        private_binary=executable.is_relative_to(tools)
        app_argument=any(str(root/'backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar')==arg for arg in argv)
        owned_script=any((Path(arg).name.endswith(('-smoke.py','-smoke.mjs')) or Path(arg).name in ['smoke.py','pg_fixture.py']) and (cwd==root or (Path(arg).is_absolute() and Path(arg).is_relative_to(root/'scripts'))) for arg in argv)
        if private_binary or app_argument or owned_script:
            # Do not retain a full command line: classify exact ownership without copying any credential values.
            owned.append({'pid':int(item.name),'executable':str(executable),'cwd':str(cwd),'private_binary':private_binary,'app_argument':app_argument,'owned_script':owned_script})
    except (FileNotFoundError,PermissionError,ProcessLookupError):continue
markers=[];issues=[]
for name,kind,pattern in [('linux-short-tmp.json','owned-linux-short-tmp',r'/tmp/ff-[A-Za-z0-9_-]{8}'),('node-short-tmp.json','owned-linux-short-tmp-node',r'/tmp/fire-node-[A-Za-z0-9]{6}')]:
    for marker in runtime.rglob(name):
        value=json.loads(marker.read_text(encoding='utf8'));directory=Path(value['directory'])
        assert value['kind']==kind and value['uid']==uid and value['mode']==0o700 and re.fullmatch(pattern,str(directory))
        source=Path(value.get('source_tmp',value.get('source_run','')));assert source.is_relative_to(runtime)
        present=directory.exists() or directory.is_symlink();identity_matches=None
        if present:
            current=directory.lstat();identity_matches=stat.S_ISDIR(current.st_mode) and not stat.S_ISLNK(current.st_mode) and current.st_uid==uid and stat.S_IMODE(current.st_mode)==0o700 and current.st_dev==int(value['device']) and current.st_ino==int(value['inode'])
            issues.append({'marker':str(marker.relative_to(root)),'directory':str(directory),'identity_matches':identity_matches})
        markers.append({'marker':str(marker.relative_to(root)),'directory':str(directory),'exists':present,'state':value.get('state'),'identity_matches':identity_matches})
pid_markers=[str(path.relative_to(root)) for path in runtime.rglob('postmaster.pid')]
result={'status':'idle' if not owned and not issues and not pid_markers else 'active-or-unconfirmed','observed_at':datetime.now(timezone.utc).isoformat(),'root':str(root),'uid':uid,'owned_processes':owned,'short_tmp_markers':markers,'short_tmp_remaining':issues,'postgres_pid_markers':pid_markers,
        'boundary':'Read-only exact-workspace Linux resource census, no broad kill/delete/service operation. Private tool binaries belong to this new workspace; argv is classified not emitted. Only marker-bound shorttmp names/uid/dev/inode/mode are inspected. Absence at this capture is finite, not a systemwide cleanup/security claim.'}
if args.output:
    output=Path(args.output).resolve();assert output.is_relative_to(root/'artifacts/ubuntu-linux') and not output.exists()
    with output.open('x',encoding='utf8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
print(json.dumps({'status':result['status'],'owned_processes':len(owned),'short_tmp_markers':len(markers),'short_tmp_remaining':len(issues),'postgres_pid_markers':len(pid_markers)}))
if args.require_idle:assert result['status']=='idle', 'Owned resources remain; inspect exact identity, never broadly kill/delete'
