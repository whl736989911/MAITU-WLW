#!/usr/bin/env python3
"""用本仓 codec 沙箱跑旧平台导出的 pa_code + 真实样本，与平台 parse 对比。"""
import json, pathlib, subprocess, sys, math

ROOT = pathlib.Path('/mnt/d/烟感/fire-platform')
BASE = pathlib.Path('/mnt/c/Users/73698/Downloads/20261102/round2')
RUNNER = ROOT / 'scripts/codec-sandbox/run.mjs'

CASES = [
    ('323', '323-JTY-GD-TCN1010.txt', 'json-raw'),
    ('500', '500-JTY-GD-TCN2010TC.txt', 'json-raw'),
    ('480', '480-JTY-GD-TCN2010_L.txt', 'data-hex'),
    ('334', '334-TBS-110.txt', 'data-hex'),
    ('119', '119-TBS-N101.txt', 'payload-hex'),
]

def sample(name):
    text = (BASE / 'B-samples' / name).read_text(encoding='utf-8')
    lines = [line for line in text.splitlines()]
    raw = next(line for line in lines if line.startswith('{"'))
    idx = lines.index('## platform parse (rx_timeline[1].data)')
    expected = json.loads(lines[idx + 1])
    return json.loads(raw), expected

def same(a, b, path=''):
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(float(a), float(b), rel_tol=1e-9, abs_tol=1e-6), path
    if isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b): return False, f'{path}: keys {sorted(set(a) ^ set(b))[:6]}'
        for k in a:
            ok, why = same(a[k], b[k], f'{path}/{k}')
            if not ok: return ok, why
        return True, ''
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b): return False, f'{path}: len {len(a)}!={len(b)}'
        for i, (x, y) in enumerate(zip(a, b)):
            ok, why = same(x, y, f'{path}[{i}]')
            if not ok: return ok, why
        return True, ''
    return a == b, f'{path}: {a!r} != {b!r}'

def payload_hex(mode, raw):
    if mode == 'json-raw':
        return json.dumps(raw, ensure_ascii=False, separators=(',', ':')).encode().hex()
    if mode == 'data-hex':
        return raw['data']
    if mode == 'payload-hex':
        return raw['service']['data']['payload']
    raise AssertionError(mode)

results = []
for product, file, mode in CASES:
    script = (BASE / 'B-pa_code' / next(p.name for p in (BASE / 'B-pa_code').iterdir() if p.name.startswith(product + '-'))).read_text(encoding='utf-8')
    raw, expected = sample(file)
    request = json.dumps({'source': script, 'payloadHex': payload_hex(mode, raw), 'attrs': {}})
    proc = subprocess.run(['node', str(RUNNER)], input=request.encode(), capture_output=True, timeout=60)
    out = json.loads(proc.stdout.decode() or '{}')
    if not out.get('ok'):
        results.append((product, 'ERROR', out.get('error'), None))
        continue
    got = out['events']
    ok, why = same(got, expected)
    results.append((product, 'MATCH' if ok else 'DIFF', why, len(got)))

for product, status, why, n in results:
    print(product, status, (why or '')[:160], 'events=%s' % n)
bad = [r for r in results if r[1] != 'MATCH']
sys.exit(1 if bad else 0)
