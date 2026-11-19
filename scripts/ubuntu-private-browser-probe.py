"""Reproduce browser infrastructure using fixture-private env, without application credentials."""
from pathlib import Path
import os
import subprocess
import sys
import uuid

root=Path(os.environ['FIRE_VALIDATION_ROOT']).resolve();sys.path.insert(0,str(root/'scripts'))
from linux_fixture import fixture_environment
run=root/'.runtime'/('browser-platform-probe-'+uuid.uuid4().hex);run.mkdir(parents=True,mode=0o700)
env=fixture_environment(run/'tmp');env['FIRE_VALIDATION_ROOT']=str(root)
node=root/'.tools/linux/node-24.16.0/bin/node';probe=Path(__file__).with_name('ubuntu-platform-probe.mjs')
print('NO_APPLICATION_CREDENTIALS_PRIVATE_ENV_PROBE',flush=True)
child=subprocess.run([str(node),str(probe)],cwd=root,env=env,stdin=subprocess.DEVNULL)
raise SystemExit(child.returncode)
