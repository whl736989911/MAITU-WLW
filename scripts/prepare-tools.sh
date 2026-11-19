#!/usr/bin/env bash
# Ubuntu/Linux project-local Java21 + Maven. No apt, system PATH, service or browser changes.
set -euo pipefail
[[ $(uname -s) == Linux ]] || { printf '%s\n' 'Ubuntu/Linux is required.' >&2; exit 1; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export FIRE_TOOLS_DIR=${FIRE_TOOLS_DIR:-"$ROOT/.tools/linux"}
command -v python3 >/dev/null
python3 - <<'PY'
import hashlib, json, os, platform, re, tarfile, tempfile, urllib.request
from pathlib import Path
from urllib.parse import urlencode
root = Path(os.environ['FIRE_TOOLS_DIR']).expanduser()
assert root.is_absolute(), 'FIRE_TOOLS_DIR must be absolute'
root.mkdir(parents=True, exist_ok=True)
arch = platform.machine()
assert arch in ('x86_64', 'aarch64'), 'Tool preparation supports Ubuntu amd64/arm64 only'
def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        value = response.read(2_000_001)
    assert len(value) <= 2_000_000
    return value

def install(url, digest, algorithm, archive_name, destination):
    archive = root / archive_name
    if not archive.exists():
        partial = root / (archive_name + '.part')
        assert not partial.exists(), 'Interrupted download retained; inspect before retry'
        try:
            with urllib.request.urlopen(url, timeout=300) as source, partial.open('xb') as output:
                while block := source.read(1024 * 1024):
                    output.write(block)
            assert hashlib.new(algorithm, partial.read_bytes()).hexdigest() == digest
            partial.rename(archive)
        except BaseException:
            raise  # Retain this exact partial download for diagnosis, never run it.
    assert hashlib.new(algorithm, archive.read_bytes()).hexdigest() == digest, 'Cached tool checksum mismatch'
    assert not destination.exists(), 'Refusing to overwrite existing tool directory'
    with tempfile.TemporaryDirectory(prefix='tool-extract-', dir=root) as temporary:
        temporary = Path(temporary)
        with tarfile.open(archive, 'r:gz') as package:
            members = package.getmembers()
            assert len(members) < 30000 and sum(item.size for item in members) < 1_500_000_000
            package.extractall(temporary, members=members, filter='data')
        directories = list(temporary.iterdir())
        assert len(directories) == 1 and directories[0].is_dir()
        directories[0].rename(destination)
    print('Verified project-local tool:', destination)

jdk = root / 'jdk-21'
if not jdk.exists():
    query = urlencode({'java_version':'21','os':'linux','arch':'x86' if arch == 'x86_64' else 'arm','archive_type':'tar.gz','java_package_type':'jdk','release_status':'ga','availability_types':'CA','latest':'true'})
    packages = json.loads(fetch('https://api.azul.com/metadata/v1/zulu/packages/?' + query))
    suffix = 'linux_x64' if arch == 'x86_64' else 'linux_aarch64'
    candidates = [p for p in packages if suffix in p['name'] and not any(word in p['name'] for word in ['crac','-fx','musl'])]
    assert candidates, 'No matching Linux Java21 archive from metadata'
    package = json.loads(fetch('https://api.azul.com/metadata/v1/zulu/packages/' + candidates[0]['package_uuid']))
    digest = package['sha256_hash'].lower(); assert re.fullmatch('[0-9a-f]{64}', digest)
    install(package['download_url'], digest, 'sha256', 'jdk21-' + arch + '.tar.gz', jdk)
assert (jdk / 'bin/java').is_file() and os.access(jdk / 'bin/java', os.X_OK)
assert (jdk / 'bin/javac').is_file()
version = '3.9.11'; maven = root / ('apache-maven-' + version)
if not maven.exists():
    url = f'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{version}/apache-maven-{version}-bin.tar.gz'
    digest = fetch(url + '.sha512').decode('ascii').strip().split()[0].lower(); assert re.fullmatch('[0-9a-f]{128}', digest)
    install(url, digest, 'sha512', 'maven-' + version + '.tar.gz', maven)
assert (maven / 'bin/mvn').is_file() and os.access(maven / 'bin/mvn', os.X_OK)
node_version = '24.16.0'; node = root / ('node-' + node_version)
if not node.exists():
    name = 'node-v' + node_version + '-linux-' + ('x64' if arch == 'x86_64' else 'arm64') + '.tar.gz'
    base = 'https://nodejs.org/dist/v' + node_version + '/'
    sums = fetch(base + 'SHASUMS256.txt').decode('ascii').splitlines()
    matches = [line.split()[0] for line in sums if line.split()[-1] == name]
    assert len(matches) == 1 and re.fullmatch('[0-9a-f]{64}', matches[0])
    install(base + name, matches[0], 'sha256', name, node)
assert (node / 'bin/node').is_file() and os.access(node / 'bin/node', os.X_OK)
PY
export JAVA_HOME="$FIRE_TOOLS_DIR/jdk-21"
export PATH="$FIRE_TOOLS_DIR/node-24.16.0/bin:$JAVA_HOME/bin:$FIRE_TOOLS_DIR/apache-maven-3.9.11/bin:$PATH"
java --version
mvn --version
node --version
npm --version
printf '%s\n' 'Local tools ready. Build/run entry points load .tools/linux automatically; no global environment was changed.'
