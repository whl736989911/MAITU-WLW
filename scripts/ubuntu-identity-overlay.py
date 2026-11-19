"""Apply only the JVM temporary-path reference fix after the unrelated PG02 chain completes."""
from pathlib import Path
import argparse
import hashlib
import json
import platform

parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--source',required=True);args=parser.parse_args()
assert platform.system()=='Linux'
root=Path(args.root).resolve();source=Path(args.source).resolve();folder=root/'artifacts/ubuntu-linux';sha=lambda b:hashlib.sha256(b).hexdigest().upper()
census=json.loads((folder/'intermediate-cleanup-02.json').read_text(encoding='utf8'));assert census['status']=='idle'
receipt=json.loads((folder/'linux-02-baseline-pg-receipt.json').read_text(encoding='utf8'))
assert receipt['status']=='failed' and len(receipt['stages'])==13 and receipt['stages'][-1]['exit_code']!=0, 'Original PG02 ended at real unread-peer transport failure; not a completed pass'
failed=json.loads((folder/'linux-02-baseline-h2-receipt.json').read_text(encoding='utf8'));assert failed['status']=='failed' and len(failed['stages'])==4 and failed['stages'][-1]['exit_code']!=0
relative='scripts/identity-browser-smoke.mjs';before=(root/relative).read_bytes();after=(source/relative).read_bytes()
old=b"'-Djava.io.tmpdir=' + path.join(run, 'tmp')";new=b"'-Djava.io.tmpdir=' + env.TMPDIR"
assert before.count(old)==1 and before.replace(old,new)==after
manifest=json.loads((folder/'source-copy-manifest.json').read_text(encoding='utf8'));record=next(row for row in manifest['files'] if row['path']==relative);assert sha(before)==record['sha256']
history=folder/'integration-identity-tmpdir-failure-02';history.mkdir()
with (history/'identity-browser-smoke.mjs').open('xb') as output:output.write(before)
with (root/relative).open('wb') as output:output.write(after)
assert (root/relative).read_bytes()==after
proof={'status':'authorized-one-line-jvm-temp-overlay','files':[{'path':relative,'old_sha256':sha(before),'sha256':sha(after),'bytes':len(after)}],
       'pg02_exited_and_idle_before_apply':True,'boundary':'Only the original Node-owned identity JVM -Djava.io.tmpdir now uses already-owned existing env.TMPDIR instead of removed run/tmp. No assertion/request/actor/viewport/timeout/sandbox/Java/TS/JAR/dependency changes. Actual baselineH2-04 JVM log showed missingtempDir/NoSuchFileException, independent readonly review also found it. Original failed receipt/log/source kept. PG02 never invokes this standalone H2-only identity browser vector; its original partial success and transport failure remain unmodified. Actual idle census was required before applying.'}
with (folder/'identity-overlay-03.json').open('x',encoding='utf8') as output:json.dump(proof,output,ensure_ascii=False,indent=2)
print(json.dumps({'status':proof['status'],'sha256':sha(after)}))
