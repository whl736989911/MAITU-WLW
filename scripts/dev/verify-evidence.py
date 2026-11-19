#!/usr/bin/env python3
"""校验验收证据的原始字节：每张截图的大小/SHA-256 与报告声明一致，且是真实 PNG。

只读，不重跑。任何不一致都让退出码非 0。
"""
from __future__ import annotations
import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

PNG_SIG = b'\x89PNG\r\n\x1a\n'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def check_png(path: Path, record: dict) -> list[str]:
    problems: list[str] = []
    raw = path.read_bytes()
    if len(raw) != record.get('bytes'):
        problems.append(f'{path.name}: 字节数 {len(raw)} != 声明 {record.get("bytes")}')
    if sha(raw) != str(record.get('sha256', '')).upper():
        problems.append(f'{path.name}: SHA-256 与声明不符')
    if raw[:8] != PNG_SIG:
        problems.append(f'{path.name}: 不是 PNG 签名')
    elif raw[12:16] != b'IHDR':
        problems.append(f'{path.name}: 缺少 IHDR')
    else:
        width, height = struct.unpack('>II', raw[16:24])
        if width <= 0 or height <= 0:
            problems.append(f'{path.name}: 尺寸非法 {width}x{height}')
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', required=True)
    args = ap.parse_args()
    root = Path(args.dir)
    if not root.is_dir():
        print(f'NOT_A_DIR {root}')
        return 2

    problems: list[str] = []
    verified = 0
    reports = sorted(root.glob('ui-*.json'))
    if not reports:
        problems.append('没有任何 ui-*.json 报告')
    for report_path in reports:
        try:
            report = json.loads(report_path.read_text(encoding='utf8'))
        except json.JSONDecodeError as error:
            problems.append(f'{report_path.name}: 不是合法 JSON ({error})')
            continue
        if report.get('status') != 'passed':
            problems.append(f'{report_path.name}: status={report.get("status")}')
        shots = report.get('screenshots') or []
        if not shots:
            problems.append(f'{report_path.name}: 无截图记录')
        shot_dir = root / (report_path.stem + '-shots')
        if not shot_dir.is_dir():
            problems.append(f'{report_path.name}: 缺少同名截图目录 {shot_dir.name}')
        for record in shots:
            name = record.get('name')
            path = shot_dir / f'{name}.png'
            if not path.is_file():
                problems.append(f'{report_path.name}: 缺少截图文件 {path.name}')
                continue
            problems.extend(check_png(path, record))
            verified += 1

    for name in ('jar-sha256.txt', 'source-snapshot-sha256.txt'):
        path = root / name
        if not path.exists():
            problems.append(f'缺少 {name}')
        elif len(path.read_text().strip()) != 64:
            problems.append(f'{name} 不是 64 位十六进制')

    proof_path = root / 'proof.json'
    if proof_path.exists():
        proof = json.loads(proof_path.read_text(encoding='utf8'))
        if proof.get('all_pass') is not True:
            problems.append('proof.json all_pass 不是 true')
    else:
        problems.append('缺少 proof.json')

    print(json.dumps({'dir': str(root), 'reports': len(reports), 'images_verified': verified,
                      'problems': problems, 'ok': not problems}, ensure_ascii=False, indent=1))
    return 0 if not problems else 1


if __name__ == '__main__':
    sys.exit(main())
