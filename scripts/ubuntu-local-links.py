"""Check current project/Ubuntu-plan local Markdown destinations (not historic line-anchor semantics)."""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import json
import re

root=Path(__file__).resolve().parents[1]
documents=[root/name for name in ['README.md','IMPLEMENTATION.md','backend/README.md','frontend/README.md']]
documents+=sorted((root/'docs').glob('*.md'))+sorted((root.parent/'开发方案').rglob('*.md'))
checked=[];missing=[]
for document in documents:
    text=document.read_text(encoding='utf8')
    for match in re.finditer(r'\]\((?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)',text):
        raw=match.group(1) or match.group(2);url=urlsplit(raw)
        if url.scheme or url.netloc or not url.path:continue
        path=(document.parent/unquote(url.path)).resolve()
        row={'document':str(document.relative_to(root.parent)),'line':text[:match.start()].count('\n')+1,'link':raw,'target':str(path)}
        checked.append(row)
        if not path.exists():missing.append(row)
result={'status':'passed' if not missing else 'failed','documents':len(documents),'local_destinations_checked':len(checked),'missing':missing,
        'boundary':'CurrentRoot/BE/FE guides, mutable docs navigation and allcurrent developmentplan Markdown local destination existence only. Does not execute archived scripts or reinterpret historic source/line-anchor claims; immutable original handoffs remain historical and are not recursively traversed. Remote URLs/fragments/semantic intent are not certified.'}
folder=root/'artifacts/ubuntu-linux';folder.mkdir(parents=True,exist_ok=True);(folder/'local-links.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(result,ensure_ascii=False,indent=2));assert not missing
