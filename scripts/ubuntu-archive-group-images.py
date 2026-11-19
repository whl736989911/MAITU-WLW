"""Preserve eight original accepted Linux group PNGs and their safe source browser reports."""
from pathlib import Path
import argparse
import hashlib
import json
import struct
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);args=parser.parse_args();root=Path(args.root).resolve();folder=root/'artifacts/ubuntu-linux';out=folder/'screenshots';out.mkdir(exist_ok=True)
sha=lambda b:hashlib.sha256(b).hexdigest().upper();rows=[]
for mode in ['groups-h2','groups-pg']:
    receipt=json.loads((folder/('linux-02-'+mode+'-receipt.json')).read_text(encoding='utf8'));assert receipt['status']=='passed'
    stage=receipt['stages'][0];raw=(root/stage['log']).read_bytes();assert sha(raw)==stage['log_sha256']
    native=json.loads(raw);assert native['status']=='passed' and native['browser_teardown_confirmed'] and native['teardown_completed']
    source=Path(native['browser_result_path']);assert source.is_relative_to(root/'.runtime')
    browser_raw=source.read_bytes();browser=json.loads(browser_raw);assert browser['status']=='passed' and browser['teardown_completed'] and len(browser['screenshots'])==4
    report=folder/('linux-02-'+mode+'-browser-result.json');assert not report.exists();report.write_bytes(browser_raw)
    for image in browser['screenshots']:
        original=Path(image['path']);assert original.is_relative_to(source.parent) and image['original'] and image['source_label']=='REAL_UI_READ'
        data=original.read_bytes();assert len(data)==image['bytes'] and sha(data)==image['sha256'].upper() and data[:8]==b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II',data[16:24])==(image['width'],image['height'])
        destination=out/('linux-02-'+mode+'-'+image['name']+'.png');assert not destination.exists();destination.write_bytes(data);assert destination.read_bytes()==data
        rows.append({'mode':mode,'source':str(original),'archive':str(destination.relative_to(root)),'sha256':sha(data),'bytes':len(data),'width':image['width'],'height':image['height'],'original':True})
proof={'status':'eight-original-linux-images-preserved','artifact_sha256':native['artifact_sha256'],'images':rows,'lead_visual_review':'pending separate read_image; no OCR or pixel-equivalence claim','boundary':'Original bytes from the passed Linux02 group H2/PG browser runs; no crops/stitch/normalization or old Windows PNG reuse. Later support-only identity/TCP fixes do not touch group code or package.'}
with (folder/'group-images-proof.json').open('x',encoding='utf8') as stream:json.dump(proof,stream,ensure_ascii=False,indent=2)
print(json.dumps({'status':proof['status'],'images':len(rows)}))
