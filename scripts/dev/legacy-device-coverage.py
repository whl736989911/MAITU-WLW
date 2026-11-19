#!/usr/bin/env python3
"""统计旧平台设备可得覆盖率：平台总量、已导出量、字段缺口。

只读本地材料：appendix/legacy/devices_tsf.tsv、round3/devices.csv、db-schema.sql、db-tables.md。
输出 artifacts/legacy-device-coverage.json 与 docs/legacy-device-coverage.md。
"""
import collections
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
APPENDIX = pathlib.Path('D:/烟感/_incoming/omp-docs/appendix')
ROUND3 = ROOT / 'artifacts/legacy-round3/20261102/round3'

EXPORTED_COLUMNS = {
    'd_eui': '设备编号', 'd_name': '设备名称', 'd_model': '型号', 'd_pid': '产品 ID(ttl)',
    'd_pid_place': '场所 ID(tsf)', 'company': '单位', 'place': '场所', 'address': '地址',
    'status': '状态码', 'status_text': '状态文案', 'last_online': '最后消息时间',
}


def table_columns(sql, table):
    match = re.search(r'CREATE TABLE `%s` \((.*?)\n\) ENGINE' % re.escape(table), sql, re.S)
    if not match:
        return {}
    columns = {}
    for line in match.group(1).splitlines():
        field = re.match(r'\s*`([^`]+)`\s+([^\s]+)', line)
        if field:
            comment = re.search(r"COMMENT '([^']*)'", line)
            columns[field.group(1)] = comment.group(1) if comment else field.group(2)
    return columns


def main():
    sql = (APPENDIX / 'db-schema.sql').read_text(encoding='utf-8', errors='replace')
    tsf_columns = table_columns(sql, 'tsf_device')
    ttl_columns = table_columns(sql, 'iot_device')

    devices = list(csv.DictReader(open(APPENDIX / 'legacy/devices_tsf.tsv', encoding='utf-8'), delimiter='\t'))
    exported = list(csv.DictReader(open(ROUND3 / 'devices.csv', encoding='utf-8-sig')))
    org = list(csv.DictReader(open(ROUND3 / 'org.csv', encoding='utf-8-sig')))

    status_names = {'1': '离线', '2': '在线', '3': '警报', '4': '故障', '5': '未激活', '6': '屏蔽'}
    platform_status = collections.Counter(status_names.get(row['d_status'], row['d_status']) for row in devices)
    exported_companies = {row['company'].strip() for row in exported}
    platform_companies = {row['d_company_id'] for row in devices}
    exported_euis = {row['d_eui'] for row in exported}
    platform_models = collections.Counter(row['d_model'] for row in devices)
    exported_models = collections.Counter(row['d_model'] for row in exported)

    appendix_fields = list(csv.DictReader(open(APPENDIX / 'legacy/devices_tsf.tsv', encoding='utf-8'), delimiter='\t'))[0].keys()
    appendix_missing = {name: desc for name, desc in tsf_columns.items() if name not in appendix_fields}
    tsf_missing = {name: desc for name, desc in tsf_columns.items() if name not in EXPORTED_COLUMNS}
    # 已导出字段的语义并不总与旧库同名：d_pid 在 ttl 是产品 id，在 tsf 是场所 id，round3 已拆成两列。
    tsf_missing.pop('d_pid', None)
    ttl_missing = {name: desc for name, desc in ttl_columns.items()}

    report = {
        'platform': {
            'tsf_devices': len(devices),
            'tsf_status': dict(platform_status),
            'tsf_models': dict(platform_models),
            'companies_with_devices': len(platform_companies),
            'companies_in_org_tree': len([row for row in org if row['type'] == 'company']),
            'db_tables_snapshot': {'tsf_device': 13865, 'iot_device': 12271, 'iot_device_tx': 5294054},
        },
        'exported': {
            'devices': len(exported),
            'coverage_percent': round(100 * len(exported) / len(devices), 2),
            'companies': sorted(exported_companies),
            'models': dict(exported_models),
            'status': dict(collections.Counter(row['status_text'] for row in exported)),
            'euis_unique': len(exported_euis),
        },
        'appendix_fields': list(appendix_fields),
        'appendix_fields_not_exported': appendix_missing,
        'missing_devices': len(devices) - len(exported),
        'missing_companies': len(platform_companies) - len(exported_companies),
        'tsf_fields_exported': EXPORTED_COLUMNS,
        'tsf_fields_not_exported': tsf_missing,
        'ttl_fields_not_exported': ttl_missing,
        'limits': {
            'iot_device_rx_retention_days': 20,
            'iot_device_tx_rows_not_exported': 5294054,
            'alarm_samples_exported': 40,
            'mqtt_uplink_gateways_visible': 8,
            'no_pa_code_models': ['JTY-GD-TCN2010-C(589, HTTP)', 'JTY-GD-TCN-2010-T(516, Java DataParse)'],
        },
    }

    out_json = ROOT / 'artifacts/legacy-device-coverage.json'
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')

    lines = [
        '# 旧平台设备获取覆盖率', '',
        '由 `scripts/dev/legacy-device-coverage.py` 从本地导出计算，源数据：`_incoming/omp-docs/appendix/legacy/devices_tsf.tsv`、`artifacts/legacy-round3/`。', '',
        '## 总量', '',
        '| 项 | 值 |', '|---|---|',
        f'| 旧平台 TSF 设备（最新导出） | {len(devices):,} |',
        f'| 设备状态 | ' + '、'.join(f'{k} {v:,}' for k, v in platform_status.most_common()) + ' |',
        f'| 有设备的单位 | {len(platform_companies)} |',
        f'| 组织树单位总数 | {report["platform"]["companies_in_org_tree"]} |',
        f'| 已导出设备（3 个单位） | {len(exported):,}（{report["exported"]["coverage_percent"]}%） |',
        f'| 未导出设备 | {report["missing_devices"]:,} |', '',
        '> `db-tables.md` 是较早快照（tsf_device 13,865 / iot_device 12,271）；以最新导出的 14,886 为准。', '',
        '## 已持有台账', '',
        f'- 基础台账：{len(devices):,} 台（`appendix/legacy/devices_tsf.tsv`，含 EUI/名称/状态/型号/单位 id/场所 id/平台设备 id/最后消息时间）。',
        f'- 详细台账：{len(exported):,} 台（round3，额外含单位/场所名称、地址、产品 id）。',
        f'- 详细台账未覆盖：{len(devices) - len(exported):,} 台、{report["missing_companies"] if "missing_companies" in report else 18} 个单位。', '',
        '## 已导出覆盖', '',
        '| 项 | 已导出 | 平台总量 |', '|---|---|---|',
    ]
    for model, total in platform_models.most_common(10):
        lines.append(f'| {model} | {exported_models.get(model, 0):,} | {total:,} |')
    lines += ['', '## 拿不到的设备', '',
              f'- 其余 {report["missing_companies"]} 个有设备的单位、{report["missing_devices"]:,} 台设备未导出（只给了 3 个单位 × 666 台）。',
              '- 589（JTY-GD-TCN2010-C）2,659 台走外部 HTTP 直推，MQTT 看不到；已导出的 1,330 台也没有 MQTT 链路。',
              '- 516（JTY-GD-TCN-2010-T）371 台无 `pa_code`，解码需 Java `DataParse`；30 天无上行。',
              '- 174（TBS-N140）99 台 30 天无上行，拿不到真实报文。', '',
              '## 拿不到的字段（tsf_device）', '',
              '| 字段 | 含义 |', '|---|---|']
    for name, desc in tsf_missing.items():
        lines.append(f'| `{name}` | {desc} |')
    lines += ['', '## 拿不到的字段（iot_device）', '',
              '| 字段 | 含义 |', '|---|---|']
    for name, desc in ttl_missing.items():
        lines.append(f'| `{name}` | {desc} |')
    lines += ['', '## 历史与通道限制', '',
              '- `iot_device_rx` 只保留 20 天；更早的上行原始报文已不可得。',
              '- `iot_device_tx`（下行）5,294,054 行未导出。',
              '- 告警只导出了 40 条样例；全量 `tsf_device_alarm` 仍缺。',
              '- 设备联系人、接收人号码、私钥、口令按只读边界不导出。', '']
    (ROOT / 'docs/legacy-device-coverage.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('missing_devices', 'missing_companies')}, ensure_ascii=False),
          len(tsf_missing), len(ttl_missing))


if __name__ == '__main__':
    raise SystemExit(main())
