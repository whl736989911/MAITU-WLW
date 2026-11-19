"""Preserve the failed unread-peer fixture and authorize only its Linux client MSS option."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import subprocess
import sys
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--source',required=True);args=parser.parse_args();assert platform.system()=='Linux'
root=Path(args.root).resolve();source=Path(args.source).resolve();folder=root/'artifacts/ubuntu-linux';sha=lambda b:hashlib.sha256(b).hexdigest().upper()
relative='scripts/alarm-channel-smoke.py';before=(root/relative).read_bytes();after=(source/relative).read_bytes()
needle=b'            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)\n'
addition=needle+b'            # A tiny unread receiver still lets Linux loopback give its sender\n            # a multi-megabyte queue, hiding backpressure from this finite case.\n            # Bound MSS before connect (verified by the credential-free probe).\n            self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG, 536)\n'
assert before.count(needle)==1 and before.replace(needle,addition)==after and sha(after)=='483CBC5B68F1754E5371FEE657744187E1D50E8224D11824B897AF6C24FD4E72'
manifest=json.loads((folder/'source-copy-manifest.json').read_text(encoding='utf8'));record=next(row for row in manifest['files'] if row['path']==relative);assert sha(before)==record['sha256']
failed=json.loads((folder/'linux-02-baseline-pg-receipt.json').read_text(encoding='utf8'));assert failed['status']=='failed' and failed['stages'][-1]['index']==13 and failed['stages'][-1]['exit_code']!=0
probe=source/'scripts/ubuntu-tcp-backpressure-probe.py';assert sha(probe.read_bytes())=='64B8030A189FFFB70D0F748178B0E4CB994B6771825F13E55FCD162230EAE3F8'
with (folder/'tcp-backpressure-root-04.json').open('xb') as output:
    child=subprocess.run([sys.executable,str(probe)],stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,timeout=30)
assert child.returncode==0
observed=json.loads((folder/'tcp-backpressure-root-04.json').read_text(encoding='utf8'));assert observed['status']=='observed' and not observed['paced'][0]['send_timeout_observed'] and observed['paced'][1]['send_timeout_observed']
history=folder/'integration-unread-peer-failure-02';history.mkdir()
with (history/'alarm-channel-smoke.py').open('xb') as output:output.write(before)
with (root/relative).open('wb') as output:output.write(after)
assert (root/relative).read_bytes()==after
proof={'status':'authorized-linux-unread-peer-transport-overlay','files':[{'path':relative,'old_sha256':sha(before),'sha256':sha(after),'bytes':len(after)}],
       'boundary':'Only receive_buffer-configured real Linux peer sets TCP_MAXSEG536 before connect. Root independently observed the credential-free finite TCP comparison; it is not application close evidence. No system/root/sysctl/server option, fake EOF/close, assertion,30s unread/15s terminal timeout, SQL/finally, Java/TS/JAR change. Actual original WebSocket case and healthy peer/HTTP/permit recovery still require runtime.'}
with (folder/'tcp-overlay-04.json').open('x',encoding='utf8') as output:json.dump(proof,output,ensure_ascii=False,indent=2)
print(json.dumps({'status':proof['status'],'sha256':sha(after)}))
