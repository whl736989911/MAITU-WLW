#!/usr/bin/env python3
"""Build a private v2 appendix from the November legacy master export; never write a database."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile
from collections import Counter
from zipfile import ZipFile


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive_files(path):
    files = {}
    seen = set()
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            key = PurePosixPath(name)
            require(not key.is_absolute() and '..' not in key.parts and name not in seen,
                    'Duplicate or unsafe archive member')
            seen.add(name)
            require(member.isdir() or member.isfile(), 'Unexpected archive member type')
            if member.isfile():
                files[name] = archive.extractfile(member).read()
    hashes = {}
    for line in files['SHA256SUMS'].decode('ascii').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line)
        require(match, 'Malformed SHA256SUMS')
        name = match[2].removeprefix('./')
        require(name not in hashes, 'Duplicate checksum member')
        hashes[name] = match[1]
    require(set(files) - {'SHA256SUMS'} == hashes.keys(), 'Archive checksum coverage differs')
    require(all(digest(files[name]) == sha for name, sha in hashes.items()), 'Archive checksum differs')
    return files


def table(data, separator=',', allowed_missing=()):
    reader = csv.DictReader(io.StringIO(data.decode('utf-8-sig'), newline=''), delimiter=separator, strict=True)
    require(reader.fieldnames and len(set(reader.fieldnames)) == len(reader.fieldnames), 'Duplicate or missing columns')
    rows = []
    for row in reader:
        require(None not in row and set(k for k, v in row.items() if v is None) == set(allowed_missing),
                f'Invalid record width at CSV line {reader.line_num}')
        rows.append({k: '' if v in (None, r'\N', 'NULL') else v for k, v in row.items()})
    return rows


def indexed(rows, *keys):
    out = {}
    for row in rows:
        key = tuple(row[k] for k in keys)
        require(all(key) and key not in out, f'Missing or duplicate key in {keys!r}')
        out[key] = row
    return out


def member(zipped, suffix):
    matches = [name for name in zipped.namelist() if name.endswith('/' + suffix)]
    require(len(matches) == 1, 'Missing or duplicate member in previous dictionary: ' + suffix)
    return zipped.read(matches[0])


def verified_attributes(new, previous):
    current = indexed(new, 'product_id', 'attribute_id')
    older = indexed(table(member(previous, 'legacy/product_attrs.tsv'), '\t'), 'pa_pid', 'pa_id')
    compare = {'type': 'pa_type', 'name': 'pa_name', 'flag': 'pa_flag', 'data_type_id': 'pa_data_type',
               'unit': 'pa_unit', 'property_type_text': 'pa_service_type'}
    for key, row in current.items():
        other = older.get(key)
        require(row['service_type'] == 'ttl' and row['source_db'] == 'iot_product_attr' and
                row['source_table'].startswith('2026-11-23 '), 'Attribute export does not have the known one-column shift')
        require(other and all(row[k] == other[v] for k, v in compare.items()),
                'Attribute rows do not agree with verified previous dictionary')
        yield (row['attribute_id'], row['product_id'], row['type'], row['name'], row['flag'],
               row['data_type_id'], row['data_range_json'], row['unit'], other['pa_property_type'], row['property_type_text'])


def verified_codecs(rows, previous):
    result = {}
    for row in rows:
        key = row['product_id']
        require(re.fullmatch(r'[0-9]+', key) and key not in result, 'Invalid or duplicate codec product ID')
        content = member(previous, f'codecs/{key}.js')
        size = int(row['bytes'])
        # The November 20 exporter appended a single LF to the original bytes.
        if content[size:] == b'\n':
            content = content[:size]
        require(len(content) == size and digest(content) == row['sha256'], 'Legacy codec size or SHA differs')
        result[key] = content
    return result


def tsv(path, header, rows, display_fields=()):
    normalized = 0
    with path.open('x', encoding='utf-8', newline='\n') as out:
        out.write('\t'.join(header) + '\n')
        for row in rows:
            require(len(row) == len(header), 'Internal TSV row width mismatch')
            cleaned = []
            for key, cell in zip(header, row):
                if key in display_fields:
                    candidate = re.sub(r'[\t\r\n]+', ' ', cell)
                    normalized += candidate != cell
                    cell = candidate
                require(not re.search(r'[\r\n\t\0]', cell), 'Unsafe character in TSV column ' + key)
                cleaned.append(cell)
            out.write('\t'.join(cleaned) + '\n')
    return normalized


def prepare(master, dictionary, output):
    raw = archive_files(master)
    counts = {r['file']: int(r['rows']) for r in table(raw['row_counts.csv'])}

    def sheet(name, allowed_missing=()):
        filename = f'{name}.csv'
        rows = table(raw['csv/' + filename], allowed_missing=allowed_missing)
        require(len(rows) == counts[filename], 'CSV row count mismatch: ' + filename)
        return rows

    companies = indexed(sheet('companies'), 'legacy_id')
    all_places = indexed(sheet('places'), 'legacy_id')
    products = indexed(sheet('products'), 'product_id')
    places = {k: p for k, p in all_places.items() if (p['company_id'],) in companies}
    tsf = indexed(sheet('devices_tsf'), 'device_no')
    ttl = indexed(sheet('devices_ttl'), 'device_no')
    indexed(tsf.values(), 'legacy_pk')
    indexed(tsf.values(), 'device_id')
    reasons = Counter()
    skipped = []
    devices = []
    for key, source in tsf.items():
        upstream = ttl.get(key)
        place = places.get((source['place_id'],))
        problems = []
        if not place or place['company_id'] != source['company_id']:
            problems.append('place_unit_mismatch')
        if source['lifecycle_status'] in {'5', '6'}:
            problems.append('inactive_or_shielded')
        if upstream is None:
            problems.append('tsf_only')
        else:
            if source['product_id'] != upstream['product_id']:
                problems.append('product_id_conflict')
            if source['device_id'] != upstream['device_id']:
                problems.append('device_id_conflict')
        product = products.get((source['product_id'],))
        if not product:
            problems.append('missing_product')
        if problems:
            skipped.append(('TSF', source['legacy_pk'], '|'.join(problems)))
            reasons.update(problems)
        else:
            devices.append((source['legacy_pk'], source['device_no'], source['device_name'], source['company_id'],
                            source['place_id'], product['model'], source['product_id'], source['device_id'], source['address']))
    for key in ttl.keys() - tsf.keys():
        skipped.append(('TTL', ttl[key]['legacy_pk'], 'ttl_only'))
        reasons['ttl_only'] += 1
    with ZipFile(dictionary) as previous:
        attrs = list(verified_attributes(sheet('product_attributes', ('snapshot_at',)), previous))
        codecs = verified_codecs(sheet('product_codecs'), previous)
    require({(k,) for k in codecs} <= products.keys(), 'Codecs reference unknown products')
    require(all((r[1],) in products for r in attrs), 'Attributes reference unknown products')
    hierarchy = sheet('org_all')
    by_org_id = indexed(hierarchy, 'type', 'id')
    require(len(by_org_id) == len(companies) + len(all_places), 'Wrong organization tree size')
    for key, p in all_places.items():
        row = by_org_id.get(('place', key[0]))
        require(row and (row['name'], row['company_id'], row['parent_id']) ==
                (p['name'], p['company_id'], p['parent_id']), 'Organization tree conflicts with position ledger')

    os.umask(0o077)
    output.mkdir(mode=0o700)
    legacy = output / 'legacy'
    legacy.mkdir()
    (output / 'codecs').mkdir()
    normal = tsv(legacy/'companies.tsv', ('c_id','c_name','c_type','c_state','expiration_date'),
                 ((p['legacy_id'],p['name'],p['company_type'],p['state'],p['valid_to']) for p in companies.values()))
    normal += tsv(legacy/'places.tsv', ('p_id','p_name','p_cid'),
                  ((p['legacy_id'],p['name'],p['company_id']) for p in places.values()))
    normal += tsv(legacy/'products.tsv', ('p_id','p_name','p_model','p_category','p_manufacturer','p_comm_type','p_platfrom_id','has_js'),
                  ((p['product_id'],p['name'],p['model'],p['category_id'],p['manufacturer'],p['comm_type_id'],p['platform_ids_raw'],p['decoder_present']) for p in products.values()))
    normal += tsv(legacy/'product_attrs.tsv', ('pa_id','pa_pid','pa_type','pa_name','pa_flag','pa_data_type','pa_data_range','pa_unit','pa_property_type','pa_service_type'),
                  attrs, ('pa_name', 'pa_data_range'))
    normal += tsv(legacy/'devices_tsf.tsv', ('d_id','d_eui','d_name','d_company_id','d_pid','d_model','d_product_id','d_device_id','d_address'),
                  devices, ('d_name', 'd_address'))
    (output/'org_all.csv').write_bytes(raw['csv/org_all.csv'])
    for key, source in codecs.items():
        (output/'codecs'/f'{key}.js').write_bytes(source)
    tsv(output/'device-exceptions.tsv', ('source','legacy_pk','reason'), sorted(skipped))
    report = {'source_manifest_sha256': digest(raw['manifest.csv']), 'companies': len(companies), 'locations': len(places),
              'locations_without_company': len(all_places) - len(places), 'products': len(products), 'attributes': len(attrs),
              'codecs': len(codecs), 'devices_ready_for_preview': len(devices), 'devices_held': len(skipped),
              'held_by_reason': dict(sorted(reasons.items())), 'display_whitespace_normalized': normal,
              'sha256': {str(p.relative_to(output)): digest(p.read_bytes()) for p in sorted(output.rglob('*')) if p.is_file()}}
    (output/'conversion-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('master', type=Path)
    parser.add_argument('previous_dictionary', type=Path)
    parser.add_argument('private_output', type=Path)
    args = parser.parse_args()
    prepare(args.master, args.previous_dictionary, args.private_output)


if __name__ == '__main__':
    main()
