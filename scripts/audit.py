#!/usr/bin/env python3
"""Structural/hash audit. Does NOT prove audible quality or uninterrupted microphone capture."""
import argparse
import hashlib
import json
from pathlib import Path
from ogg_audit import inspect_ogg

RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]

def inspect_adts(path):
    frames = 0
    duration = 0.0
    sample_rate = None
    with path.open('rb') as stream:
        while True:
            h = stream.read(7)
            if not h:
                break
            if len(h) != 7 or h[0] != 255 or h[1] & 0xf6 != 0xf0:
                raise ValueError('truncated or invalid ADTS header')
            rate_index = (h[2] >> 2) & 15
            channels = ((h[2] & 1) << 2) | (h[3] >> 6)
            size = ((h[3] & 3) << 11) | (h[4] << 3) | (h[5] >> 5)
            if rate_index >= len(RATES) or RATES[rate_index] not in (16000, 24000, 48000) or channels != 1:
                raise ValueError('unexpected sample rate or channel count')
            rate = RATES[rate_index]
            if sample_rate is not None and sample_rate != rate:
                raise ValueError('sample rate changed within a segment')
            sample_rate = rate
            if size < 7 or len(stream.read(size - 7)) != size - 7:
                raise ValueError('truncated ADTS payload')
            frames += 1
            duration += 1024 * ((h[6] & 3) + 1) / RATES[rate_index]
    if frames == 0:
        raise ValueError('empty recording')
    return {'sample_rate': sample_rate, 'frames': frames, 'encoded_seconds': round(duration, 3)}

def audit(root):
    report = {'files': [], 'events': {}, 'warnings': [], 'errors': [],
              'scope': 'Checks ADTS/Ogg framing, Ogg CRC and saved SHA-256 only; listening and device tests remain necessary.'}
    expected = {}
    for log in sorted(root.rglob('events.jsonl')):
        for number, line in enumerate(log.read_text().splitlines(), 1):
            try:
                event = json.loads(line)
                kind = event['event']
                report['events'][kind] = report['events'].get(kind, 0) + 1
                if kind == 'segment_closed':
                    expected[log.parent / event['file']] = event
                if kind in ('error', 'unclean_restart') or (kind == 'system_silenced' and event.get('value')):
                    report['warnings'].append({'log': str(log.relative_to(root)), 'line': number, 'event': event})
            except (ValueError, KeyError) as error:
                report['errors'].append(f'{log}:{number}: {error}')
    actual = set(root.rglob('*.aac')) | set(root.rglob('*.ogg'))
    for missing in expected.keys() - actual:
        report['errors'].append(f'Missing finalized segment: {missing.relative_to(root)}')
    for path in sorted(actual):
        item = {'file': str(path.relative_to(root)), 'bytes': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        try:
            item.update(inspect_ogg(path) if path.suffix == '.ogg' else inspect_adts(path))
        except ValueError as error:
            report['errors'].append(f'{item["file"]}: {error}')
        if path in expected:
            meta = expected[path]
            if path.suffix == '.aac' and 'sample_rate' in meta and item.get('sample_rate') != meta['sample_rate']:
                report['errors'].append(f'{item["file"]}: sample rate differs from event')
            if meta['sha256'] != item['sha256'] or meta['bytes'] != item['bytes']:
                report['errors'].append(f'{item["file"]}: SHA-256 or size mismatch')
        else:
            report['warnings'].append(f'{item["file"]}: no finalized segment event; may be an interrupted recording')
        report['files'].append(item)
    if not actual:
        report['errors'].append('No recording files found')
    report['encoded_hours'] = round(sum(f.get('encoded_seconds', 0) for f in report['files']) / 3600, 4)
    report['structure_and_hash_ok'] = not report['errors'] and not report['warnings']
    return report

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = audit(args.directory)
    result = args.directory / 'audit.json'
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report['structure_and_hash_ok'] else 2)
