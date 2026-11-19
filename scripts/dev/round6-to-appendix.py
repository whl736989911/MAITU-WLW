#!/usr/bin/env python3
"""把 round6 全量台账转换成导入器 appendix；联系人/GPS 不进入。"""
import csv
import pathlib
import shutil
import sys

BASE = pathlib.Path('artifacts/legacy-round6/round6')
OUT = pathlib.Path('artifacts/legacy-round6/appendix')
ROUND3 = pathlib.Path('artifacts/legacy-round3/appendix')


def rows(name):
    with open(BASE / name, encoding='utf-8-sig', newline='') as handle:
        return list(csv.DictReader(handle))


def write_tsv(path, headers, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='') as handle:
        handle.write('\t'.join(headers) + '\n')
        for row in data:
            handle.write('\t'.join('' if cell is None else str(cell).replace('\t', ' ').replace('\n', ' ') for cell in row) + '\n')


def main():
    legacy = OUT / 'legacy'
    legacy.mkdir(parents=True, exist_ok=True)
    org = rows('org_all.csv')
    companies = [r for r in org if r['type'] == 'company']
    places = [r for r in org if r['type'] == 'place']
    write_tsv(legacy / 'companies.tsv', ['c_id', 'c_name', 'c_type', 'c_state', 'expiration_date'],
              [[r['id'], r['name'], '1', '1', '2030-12-31 00:00:00'] for r in companies])
    write_tsv(legacy / 'places.tsv', ['p_id', 'p_name', 'p_cid'],
              [[r['id'], r['name'], r['company_id']] for r in places])
    products = rows('products_all.csv')
    write_tsv(legacy / 'products.tsv',
              ['p_id', 'p_name', 'p_model', 'p_category', 'p_manufacturer', 'p_comm_type',
               'p_platfrom_id', 'p_product_id', 'p_key', 'has_js'],
              [[r['p_id'], r['p_name'], r['p_model'], '1', r['p_manufacturer'], r['p_comm_type'], '', '', '', r['has_pa_code']]
               for r in products])
    devices = rows('devices_all.csv')
    disputed = {'1000130624B00894': '327', '70B3D53AF001B13A': '327', '70B3D53AF001B993': '327',
                '70B3D53AF001F835': '327', '70B3D53AF00A3B4A': '327'}
    write_tsv(legacy / 'devices_tsf.tsv',
              ['d_id', 'd_eui', 'd_name', 'd_status', 'd_company_id', 'd_pid', 'd_type',
               'd_model', 'd_last_msg_time', 'd_device_id', 'd_address'],
              [[r['eui'], r['eui'], r['tsf_d_name'], r['tsf_d_status'], disputed.get(r['eui'], r['tsf_d_company_id']),
                r['place_id'], r['tsf_d_type'], r['tsf_d_model'], r['tsf_d_last_msg_time'], r['tsf_d_device_id'], r['tsf_d_address']]
               for r in devices])
    # 属性与解码器沿用 round3 已有材料
    for name in ['product_attrs.tsv']:
        shutil.copyfile(ROUND3 / 'legacy' / name, legacy / name)
    codecs = OUT / 'codecs'
    if codecs.exists():
        shutil.rmtree(codecs)
    shutil.copytree(ROUND3 / 'codecs', codecs)
    print({'companies': len(companies), 'places': len(places), 'products': len(products),
           'devices': len(devices), 'codecs': len(list(codecs.glob('*.js')))})


if __name__ == '__main__':
    main()
