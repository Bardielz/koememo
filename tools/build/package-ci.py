#!/usr/bin/env python3
"""Package a verified debug build; never exports signing keys or device files."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

root = Path(__file__).resolve().parents[2]
apks = root / 'apps/watch/wearos/app/build/outputs/apk/debug'
metadata = json.loads((apks / 'output-metadata.json').read_text())
assert len(metadata['elements']) == 1, 'Expected one universal APK'
element = metadata['elements'][0]
version = element['versionName']
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version), 'Invalid version'
ref = os.environ.get('GITHUB_REF', '')
if ref.startswith('refs/tags/'):
    assert ref == f'refs/tags/v{version}', 'Git tag must match Android versionName'
source = apks / element['outputFile']
assert source.resolve().parent == apks.resolve(), 'Unexpected APK path'
destination = root / 'dist'
destination.mkdir(exist_ok=True)
name = f'KoeMemo-{version}-ci-debug.apk'
target = destination / name
shutil.copyfile(source, target)
(target.with_suffix('.apk.sha256')).write_text(f'{hashlib.sha256(target.read_bytes()).hexdigest()}  {name}\n')
(destination / 'ci-build-info.json').write_text(json.dumps({
    'version': version, 'version_code': element['versionCode'],
    'commit': os.environ.get('GITHUB_SHA'), 'run_id': os.environ.get('GITHUB_RUN_ID'),
    'signing': 'Temporary CI debug key; may not update an existing installation.',
}, indent=2) + '\n')
