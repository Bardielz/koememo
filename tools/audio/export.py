#!/usr/bin/env python3
"""Export a paused debug build via paired ADB. Keeps all files on the watch."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET
from audit import audit

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination', type=Path, help='New destination directory, must not exist')
parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
parser.add_argument('--serial', help='Watch device serial / IP:connection-port')
args = parser.parse_args()
adb = [args.adb] + (['-s', args.serial] if args.serial else [])
settings = subprocess.check_output(adb + ['exec-out', 'run-as', 'dev.watchmemory', 'cat', 'shared_prefs/recording.xml'])
xml = ET.fromstring(settings)
if any(node.attrib.get('name') == 'recording' and node.attrib.get('value') == 'true' for node in xml):
    raise SystemExit('请先在手表暂停或结束录音，等待保存完成。若上次崩溃，请先重新打开应用。')
args.destination.mkdir(parents=True, exist_ok=False)
partial = args.destination / 'recordings.tar.part'
with partial.open('wb') as target:
    subprocess.run(adb + ['exec-out', 'run-as', 'dev.watchmemory', 'tar', '-cf', '-', 'files/recordings'], stdout=target, check=True)
archive = partial.with_suffix('')
partial.rename(archive)
with tarfile.open(archive) as bundle:
    base = args.destination.resolve()
    for member in bundle.getmembers():
        path = (base / member.name).resolve()
        if base not in path.parents or not (member.isdir() or member.isfile()):
            raise SystemExit('Unsafe archive entry rejected')
        if member.isdir():
            path.mkdir(parents=True, exist_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(member) as src, path.open('xb') as dst:
                import shutil
                shutil.copyfileobj(src, dst)
report = audit(args.destination / 'files' / 'recordings')
(args.destination / 'audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(f'已导出 {len(report["files"])} 个片段；原文件仍保留在手表。')
print(f'结构/校验结果：{report["structure_and_hash_ok"]}；报告：{args.destination / "audit.json"}')
raise SystemExit(0 if report['structure_and_hash_ok'] else 2)
