#!/usr/bin/env python3
"""把旧平台 round3 只读导出转换成 LegacyDictionaryImporter 使用的 appendix 目录。

不修改旧平台任何数据；只读本地文件，输出到 artifacts/legacy-round3/appendix。
用法：python3 scripts/dev/round3-to-appendix.py [round3_dir] [out_dir]
"""
import csv
import hashlib
import json
import pathlib
import shutil
import sys

DEFAULT_BASE = pathlib.Path('artifacts/legacy-round3/20261102/round3')
DEFAULT_OUT = pathlib.Path('artifacts/legacy-round3/appendix')
B_PRODUCTS = pathlib.Path('C:/Users/73698/Downloads/20261102/round2/B-products.csv')


def read_csv(path):
    with open(path, encoding='utf-8-sig', newline='') as handle:
        return list(csv.DictReader(handle))


def write_tsv(path, headers, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='') as handle:
        handle.write('\t'.join(headers) + '\n')
        for row in rows:
            handle.write('\t'.join('' if cell is None else str(cell).replace('\t', ' ').replace('\n', ' ') for cell in row) + '\n')


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main():
    base = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_BASE
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUT
    legacy = out / 'legacy'
    codecs = out / 'codecs'
    legacy.mkdir(parents=True, exist_ok=True)
    codecs.mkdir(parents=True, exist_ok=True)

    org = read_csv(base / 'org.csv')
    companies = [row for row in org if row['type'] == 'company']
    places = [row for row in org if row['type'] == 'place']
    company_ids = {row['name'].strip(): row['id'] for row in companies}

    write_tsv(legacy / 'companies.tsv', ['c_id', 'c_name', 'c_type', 'c_state', 'expiration_date'],
              [[row['id'], row['name'], '1', '1', '2030-12-31 00:00:00'] for row in companies])
    write_tsv(legacy / 'places.tsv', ['p_id', 'p_name', 'p_cid'],
              [[row['id'], row['name'], row['company_id']] for row in places])

    products = read_csv(B_PRODUCTS)
    write_tsv(legacy / 'products.tsv',
              ['p_id', 'p_name', 'p_model', 'p_category', 'p_manufacturer', 'p_comm_type',
               'p_platfrom_id', 'p_product_id', 'p_key', 'has_js'],
              [[row['p_id'], row['p_name'], row['p_model'], '1', row['p_manufacturer'],
                row['p_comm_type'], '', '', '', '1'] for row in products])

    attrs = read_csv(base / 'product_attr.csv') + read_csv(base / 'product_attr_part2.csv')
    write_tsv(legacy / 'product_attrs.tsv',
              ['pa_id', 'pa_pid', 'pa_type', 'pa_name', 'pa_flag', 'pa_data_type', 'pa_data_range',
               'pa_unit', 'pa_property_type', 'pa_service_type'],
              [[row['pa_id'], row['pa_pid'], row['pa_type'], row['pa_name'], row['pa_flag'],
                row['pa_data_type'], row['pa_data_range'], row['pa_unit'], '', ''] for row in attrs])

    devices = read_csv(base / 'devices.csv')
    write_tsv(legacy / 'devices_tsf.tsv',
              ['d_id', 'd_eui', 'd_name', 'd_status', 'd_company_id', 'd_pid', 'd_type',
               'd_model', 'd_last_msg_time', 'd_device_id'],
              [[row['d_eui'], row['d_eui'], row['d_name'], row['status'],
                company_ids.get(row['company'].strip(), ''), row['d_pid_place'], '1',
                row['d_model'], row['last_online'], row['d_eui']] for row in devices])

    # 解码器：round2 已导出的 6 个 + round3 的 57 个
    source_dir = pathlib.Path('C:/Users/73698/Downloads/20261102/round2/B-pa_code')
    copied = 0
    for js in sorted(source_dir.glob('*.js')):
        shutil.copyfile(js, codecs / f"{js.name.split('-')[0]}.js")
        copied += 1
    round2_pids = {path.stem for path in codecs.glob('*.js')}
    for row in read_csv(base / 'pa_code.csv'):
        pid = row['p_id'].strip()
        if pid in round2_pids:
            continue
        path = codecs / f'{pid}.js'
        path.write_text(row['pa_code'], encoding='utf-8')
        copied += 1

    report = {
        'source': str(base),
        'output': str(out),
        'companies': len(companies),
        'places': len(places),
        'products': len(products),
        'attributes': len(attrs),
        'devices': len(devices),
        'devices_by_company': {name: sum(1 for row in devices if row['company'].strip() == name) for name in company_ids},
        'devices_with_unknown_company': sum(1 for row in devices if row['company'].strip() not in company_ids),
        'devices_with_unknown_place': sum(1 for row in devices if row['d_pid_place'] not in {p['id'] for p in places}),
        'codecs': copied,
        'files': {str(path.relative_to(out)): sha256(path) for path in sorted(out.rglob('*')) if path.is_file()},
    }
    (out / 'conversion-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('companies', 'places', 'products', 'attributes', 'devices', 'codecs',
                                                   'devices_with_unknown_company', 'devices_with_unknown_place')}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
