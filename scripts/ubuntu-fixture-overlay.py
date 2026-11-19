"""Archive the exact rejected Linux helpers, then apply only two authorized fixture-plumbing fixes."""
from pathlib import Path
import argparse
import hashlib
import json
import platform

parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--source');parser.add_argument('--python-sha');parser.add_argument('--node-sha');parser.add_argument('--archive-only',action='store_true');args=parser.parse_args()
assert platform.system()=='Linux'
root=Path(args.root).resolve();folder=root/'artifacts/ubuntu-linux';history=folder/'integration-launch-failure-01'
paths=['scripts/linux_fixture.py','scripts/browser-fixture.mjs'];sha=lambda b:hashlib.sha256(b).hexdigest().upper()
manifest=json.loads((folder/'source-copy-manifest.json').read_text(encoding='utf8')); originals={r['path']:r for r in manifest['files']}
if args.archive_only:
    history.mkdir()
    rows=[]
    for relative in paths:
        raw=(root/relative).read_bytes();assert sha(raw)==originals[relative]['sha256']
        with (history/Path(relative).name).open('xb') as output:output.write(raw)
        rows.append({'path':relative,'sha256':sha(raw),'bytes':len(raw)})
    for mode in ['groups-h2','groups-pg']:
        receipt=json.loads((folder/('linux-01-'+mode+'-receipt.json')).read_text(encoding='utf8'));assert receipt['status']=='failed' and receipt['stages'][0]['exit_code']!=0
    proof={'status':'rejected-helper-bytes-preserved','files':rows,'boundary':'OriginalLinux01 H2/PG group launch failures and exclusive logs stay unchanged. Credential-free private-environment probe observed FATAL SingletonSocket path too long, not a business assertion failure or sandbox failure. No current pass is claimed here.'}
    with (history/'proof.json').open('x',encoding='utf8') as output:json.dump(proof,output,ensure_ascii=False,indent=2)
    print(json.dumps({'status':proof['status'],'files':len(rows)}))
else:
    assert args.source and args.python_sha and args.node_sha and (history/'proof.json').is_file()
    source=Path(args.source).resolve();wanted=[args.python_sha.upper(),args.node_sha.upper()];rows=[]
    for relative,digest in zip(paths,wanted):
        before=(root/relative).read_bytes();assert before==(history/Path(relative).name).read_bytes() and sha(before)==originals[relative]['sha256']
        after=(source/relative).read_bytes();assert sha(after)==digest and before!=after
        rows.append({'path':relative,'old_sha256':sha(before),'sha256':digest,'bytes':len(after)})
    for relative,digest in zip(paths,wanted):
        raw=(source/relative).read_bytes();assert sha(raw)==digest
        with (root/relative).open('wb') as output:output.write(raw)
        assert (root/relative).read_bytes()==raw
    proof={'status':'authorized-fixture-overlay','files':rows,'boundary':'Only two Linux tool/environment helpers fix private short real/tmp lifecycle for Chromium AF_UNIX. No smoke scenario/SQL/timeout/assertion/viewport/actor/sandbox/JAR/Java/TS/dependency changes. Original405 source-copy observation remains immutable; future runtime explicitly binds this overlay.'}
    with (folder/'fixture-overlay-02.json').open('x',encoding='utf8') as output:json.dump(proof,output,ensure_ascii=False,indent=2)
    print(json.dumps({'status':proof['status'],'files':len(rows)}))
