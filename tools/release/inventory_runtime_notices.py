#!/usr/bin/env python3
"""Inventory resolved release artifacts, POM license declarations, and embedded notices.

This preserves evidence; it does not establish complete redistribution compliance.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
NOTICE_NAME = re.compile(r'(LICENSE(?:[-_.][^/]*)?|NOTICE(?:[-_.][^/]*)?|COPYING(?:[-_.][^/]*)?|DEPENDENCIES(?:\.txt)?)', re.I)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def pom_licenses(group, name, version, seen=None):
    seen = set() if seen is None else seen
    identity = (group, name, version)
    if identity in seen:
        return []
    seen.add(identity)
    poms = sorted((CACHE / group / name / version).rglob('*.pom'))
    if not poms or len({sha256(p.read_bytes()) for p in poms}) != 1:
        return []
    data = poms[0].read_bytes()
    document = ET.fromstring(data)
    for node in document.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    entries = [
        {'name': (node.findtext('name') or '').strip(),
         'url': (node.findtext('url') or '').strip(),
         'declaredBy': ':'.join(identity), 'pomSha256': sha256(data)}
        for node in document.findall('licenses/license')
    ]
    if entries:
        return entries
    parent = document.find('parent')
    if parent is not None:
        coordinates = [parent.findtext(key) for key in ('groupId', 'artifactId', 'version')]
        if all(coordinates):
            return pom_licenses(*coordinates, seen=seen)
    return []


def inspect_artifact(record, payloads):
    component = record['component']
    coordinates = component.split(':')
    if len(coordinates) != 3 or not all(re.fullmatch(r'[A-Za-z0-9_.-]+', value) for value in coordinates):
        raise ValueError(f'Invalid module coordinate: {component}')
    path = Path(record['file'])
    artifact = path.read_bytes()
    notices, fonts = [], []

    def scan(archive, prefix=''):
        for entry in archive.infolist():
            name = entry.filename
            if entry.is_dir():
                continue
            if NOTICE_NAME.fullmatch(Path(name).name) and not name.endswith('.class'):
                data = archive.read(entry)
                data.decode('utf-8')
                digest = sha256(data)
                output = f'third_party/runtime-notices/{digest}.txt'
                payloads[output] = data
                notices.append({'entry': prefix + name, 'path': output,
                                'sha256': digest, 'sizeBytes': len(data)})
            if name.lower().endswith(('.ttf', '.otf', '.woff', '.woff2')):
                fonts.append(prefix + name)
            if name == 'classes.jar':
                with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as nested:
                    scan(nested, 'classes.jar/')

    with zipfile.ZipFile(io.BytesIO(artifact)) as archive:
        scan(archive)
    return {'component': component, 'artifact': path.name, 'sha256': sha256(artifact),
            'type': record['type'], 'pomLicenses': pom_licenses(*coordinates),
            'embeddedNotices': notices, 'bundledFonts': fonts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--platform', choices=['android', 'ios'], required=True)
    parser.add_argument('--check', action='store_true', help='Compare against reviewed files without writing')
    args = parser.parse_args()
    payloads = {}
    report = [inspect_artifact(record, payloads) for record in json.loads(args.inventory.read_text())]
    missing = [record['component'] for record in report if not record['pomLicenses']]
    if missing:
        parser.exit(1, f'FAIL missing or ambiguous POM license metadata: {missing}\n')
    relative = f'third_party/{args.platform}-runtime-inventory.json'
    payloads[relative] = (json.dumps(report, indent=2) + '\n').encode('utf-8')
    for relative, data in payloads.items():
        output = ROOT / relative
        if args.check:
            if not output.is_file() or output.read_bytes() != data:
                parser.exit(1, f'FAIL runtime license evidence changed; review and refresh: {relative}\n')
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
    print(f'{args.platform.upper()}_RUNTIME_ARTIFACTS={len(report)}')
    print(f'EMBEDDED_NOTICE_ENTRIES={sum(len(record["embeddedNotices"]) for record in report)}')
    print('RUNTIME_NOTICE_INVENTORY_PASSED')


if __name__ == '__main__':
    main()
