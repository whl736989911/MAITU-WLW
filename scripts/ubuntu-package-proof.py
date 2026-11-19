"""Verify the actual Ubuntu full package, original business-source bytes and current assets."""
from pathlib import Path
from html.parser import HTMLParser
from zipfile import ZipFile
import argparse
import hashlib
import json
import platform
import re
import shutil
import struct
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser(); parser.add_argument('--root',required=True); parser.add_argument('--baseline',required=True); parser.add_argument('--legacy-jar',required=True); args=parser.parse_args()
assert platform.system()=='Linux' and __debug__
root=Path(args.root).resolve(); baseline=Path(args.baseline).resolve(); oldjar=Path(args.legacy_jar).resolve()
sha=lambda b:hashlib.sha256(b).hexdigest().upper()
legacysha='6B2EB082EDE12622656B69D484855B425D620F0DBA7FFDBC1671E053BAE33439'
assert sha(oldjar.read_bytes())==legacysha
folder=root/'artifacts/ubuntu-linux'
strip=lambda text:re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text)
verify=strip((folder/'verify-01.log').read_text(encoding='utf8')); package=strip((folder/'package-01.log').read_text(encoding='utf8'))
assert 'Tests run: 363, Failures: 0, Errors: 0, Skipped: 0' in verify and '[INFO] BUILD SUCCESS' in verify
assert re.search(r'Test Files\s+20 passed\s+\(20\)',verify) and re.search(r'Tests\s+494 passed\s+\(494\)',verify)
assert 'Tests run: 363, Failures: 0, Errors: 0, Skipped: 0' in package and '[INFO] BUILD SUCCESS' in package
suites=sorted((root/'backend/target/surefire-reports').glob('TEST-*.xml')); assert len(suites)==49
counts=[0,0,0,0]
for path in suites:
    suite=ET.fromstring(path.read_bytes())
    for index,key in enumerate(['tests','failures','errors','skipped']):counts[index]+=int(suite.attrib.get(key,0))
assert counts==[363,0,0,0]
manifest=json.loads((baseline/'manifest.json').read_text(encoding='utf8'))
source_checks=[]
for record in manifest['files']:
    relative=record['path']
    business=relative.startswith('backend/src/') or relative.startswith('frontend/src/') or relative in ['backend/pom.xml','frontend/package.json','frontend/package-lock.json','frontend/vite.config.ts','frontend/vitest.config.ts']
    if business:
        assert sha((root/relative).read_bytes())==record['sha256'], 'Application source/assertion/dependency changed: '+relative
        source_checks.append(relative)
jar=root/'backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar'; data=jar.read_bytes(); artifact=sha(data)
assets={p.relative_to(root/'frontend/dist').as_posix():p.read_bytes() for p in (root/'frontend/dist').rglob('*') if p.is_file()}
with ZipFile(jar) as current, ZipFile(oldjar) as old:
    names=current.namelist(); assert len(names)==len(set(names))
    static={name.removeprefix('BOOT-INF/classes/static/'):current.read(name) for name in names if name.startswith('BOOT-INF/classes/static/') and not name.endswith('/')}
    assert static==assets and len(assets)==46
    migrations={name:current.read(name) for name in names if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}
    assert len(migrations)==13
    old_migrations={name:old.read(name) for name in old.namelist() if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}
    assert old_migrations==migrations, 'Ubuntu migration must not change existing V1..V13'
    dependencies={name:current.read(name) for name in names if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')}
    assert dependencies=={name:old.read(name) for name in old.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')}
    classes=[name for name in names if name.startswith('BOOT-INF/classes/') and name.endswith('.class')]
    assert classes and all(current.read(name)[:4]==b'\xca\xfe\xba\xbe' and struct.unpack('>H',current.read(name)[6:8])[0]==65 for name in classes)
class Eager(HTMLParser):
    def __init__(self):super().__init__();self.paths=[]
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if tag=='script' and attrs.get('type')=='module' and attrs.get('src'):self.paths.append(attrs['src'])
        if tag=='link' and attrs.get('rel')=='modulepreload' and attrs.get('href','').endswith('.js'):self.paths.append(attrs['href'])
html=Eager();html.feed(assets['index.html'].decode('utf8')); assert html.paths and len(html.paths)==len(set(html.paths))
eager=sum(len(assets[p.lstrip('/')]) for p in html.paths);assert eager<500000
copy=folder/('fire-platform-ubuntu-'+artifact[:16]+'.jar');assert not copy.exists()
with copy.open('xb') as output:output.write(data)
assert copy.read_bytes()==data
result={'status':'passed','platform':platform.platform(),'artifact_sha256':artifact,'bytes':len(data),'artifact':str(copy),'combined_tests':857,'backend_tests':363,'backend_suites':49,'frontend_tests':494,'frontend_files':20,
        'static_files':len(assets),'initial_js_bytes_including_eager_common':eager,'business_source_paths_byte_identical':source_checks,'unchanged_migrations':13,'unchanged_dependency_jars':len(dependencies),'java21_classes':len(classes),
        'files':[{'path':p,'bytes':len(b),'sha256':sha(b)} for p,b in sorted(assets.items())],
        'logs':[{'path':str(p.relative_to(root)),'sha256':sha(p.read_bytes())} for p in [folder/'verify-01.log',folder/'package-01.log']],
        'boundary':'Actual Ubuntu24.04 WSL2 nonroot build/test, not reuse of old host tests. Old6B2E only authenticates unchanged business/13migration/dependency inventory; compiled bytes/assets/newSHA are current Linux output, not claimed identical across JDK/compiler/document scan inputs. Verify and package repeat363, counted once857. Not native-production/remoteCI/vendor/security/HA certification.'}
with (folder/'package-proof.json').open('x',encoding='utf8') as output:json.dump(result,output,ensure_ascii=False,indent=2)
print(json.dumps({key:result[key] for key in ['status','artifact_sha256','bytes','combined_tests','static_files','initial_js_bytes_including_eager_common']}))
