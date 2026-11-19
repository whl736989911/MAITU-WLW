"""Download the fixed Linux-amd64 PG17 test archive; no extraction, database or service startup."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import urllib.request

parser = argparse.ArgumentParser(); parser.add_argument('--tools', required=True); args = parser.parse_args()
assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'This pinned fixture archive is Ubuntu/Linux amd64 only'
root = Path(args.tools).resolve(); root.mkdir(parents=True,exist_ok=True)
folder = root / 'downloads'; folder.mkdir(exist_ok=True)
name = 'embedded-postgres-binaries-linux-amd64-17.11.0.jar'
url = 'https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/17.11.0/' + name
expected = '0dd7b72b6f335b8ecfb355fa24c5781e8a93edd09880bb77eb52ebbf29b3e96d'
archive = folder / name
if not archive.exists():
    partial = folder / (name + '.part'); assert not partial.exists(), 'Inspect retained partial download before retry'
    with urllib.request.urlopen(url,timeout=300) as source, partial.open('xb') as output:
        while block := source.read(1024*1024): output.write(block)
    assert hashlib.sha256(partial.read_bytes()).hexdigest() == expected, 'Archive checksum mismatch'
    partial.rename(archive)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
print(json.dumps({'status':'downloaded-verified-not-started','archive':str(archive),'sha256':expected,'bytes':archive.stat().st_size,'source':url}))
