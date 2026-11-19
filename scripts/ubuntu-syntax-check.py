"""Linux source syntax only; never import fixtures or start app/tool services."""
from pathlib import Path
import argparse
import ast
import json
import platform
import subprocess
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--source',required=True);args=parser.parse_args();assert platform.system()=='Linux'
root=Path(args.root).resolve();source=Path(args.source).resolve();scripts=source/'scripts';node=root/'.tools/linux/node-24.16.0/bin/node';rows=[]
for path in sorted(scripts.iterdir()):
    if path.suffix=='.py':ast.parse(path.read_text(encoding='utf8'),filename=str(path));kind='python-ast'
    elif path.suffix=='.mjs':subprocess.run([str(node),'--check',str(path)],check=True);kind='node-check'
    elif path.suffix=='.sh':subprocess.run(['/bin/bash','-n',str(path)],check=True);kind='bash-n'
    else:continue
    rows.append({'path':str(path.relative_to(source)),'kind':kind})
result={'status':'passed','files':rows,'boundary':'ActualLinux syntax-only AST/node--check/bash-n; no fixture/application import, execution, services or claimedruntime coverage.'}
with (root/'artifacts/ubuntu-linux/syntax-check-final.json').open('x',encoding='utf8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
print(json.dumps({'status':'passed','python':sum(row['kind']=='python-ast' for row in rows),'node':sum(row['kind']=='node-check' for row in rows),'bash':sum(row['kind']=='bash-n' for row in rows)}))
