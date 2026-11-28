#!/usr/bin/env python3
"""Map sensor acquisition times to captured samples and the saved run's media timeline."""
import argparse
import bisect
import json
from pathlib import Path


def events(path):
    with path.open() as stream:
        for line in stream:
            yield json.loads(line)


def align(path):
    runs = {}
    active = None
    for e in events(path):
        kind = e['event']
        if kind == 'run_started':
            active = runs[e['run']] = dict(rate=e.get('sample_rate'), anchors=[], gaps=[], spans=[], paused=None, end=None)
        elif active is not None:
            if 'audio_boottime_ns' in e:
                active['anchors'].append((e['audio_boottime_ns'], e['audio_frame_position']))
            if 'capture_end_samples' in e:
                active['end'] = e['capture_end_samples']
            if kind == 'segment_closed' and 'playback_start_us' in e and 'end_pts_us' in e:
                active['spans'].append((e['playback_start_us'], e['end_pts_us'], e['file']))
            if kind == 'quiet_gate':
                if e['action'] == 'pause':
                    active['paused'] = e['captured_samples'] - e['held_samples']
                elif active['paused'] is not None:
                    first = active['paused']
                    active['gaps'].append((first, first + e['held_samples'] - e['replay_samples']))
                    active['paused'] = None
            if kind in ('run_stopped', 'error'):
                active = None
    for run in runs.values():
        run['anchors'].sort()
    for e in events(path):
        if e['event'] != 'sensor_batch':
            continue
        run = runs[e['run']]
        anchors = run['anchors']
        for ns, sensor, accuracy, *values in e['samples']:
            frame = media_ms = segment_file = segment_ms = None
            status = 'no_audio_anchor'
            if anchors and run['rate']:
                idx = bisect.bisect_left(anchors, (ns,))
                ref_ns, ref_frame = min(anchors[max(0, idx-1):idx+1], key=lambda a: abs(a[0]-ns))
                frame = ref_frame + round((ns - ref_ns) * run['rate'] / 1e9)
                status = 'outside_audio'
                end = run['end'] if run['end'] is not None else anchors[-1][1] + 1
                if frame >= 0 and frame < end:
                    status = 'aligned'
                    skipped = 0
                    for start, end in run['gaps']:
                        if frame < start:
                            break
                        if frame < end:
                            status = 'omitted'
                            break
                        skipped += end-start
                    if run['paused'] is not None and frame >= run['paused']:
                        status = 'unfinished_gap'
                    if status == 'aligned':
                        media_us = (frame-skipped)*1_000_000 // run['rate']
                        media_ms = media_us / 1000
                        for first, last, name in reversed(run['spans']):
                            if first <= media_us < last:
                                segment_file = name
                                segment_ms = (media_us-first) / 1000
                                break
            quality = None
            if sensor == 21:
                if accuracy < 0:
                    quality = 'no_contact'
                elif accuracy == 0 or not values or values[0] is None or values[0] <= 0:
                    quality = 'unreliable'
                else:
                    quality = 'reported'
            yield dict(heart_rate_quality=quality, bpm=values[0] if quality == 'reported' else None,
                       run=e['run'], boottime_ns=ns, type=sensor, accuracy=accuracy, values=values,
                       captured_sample=frame, media_ms=media_ms, segment_file=segment_file, segment_ms=segment_ms, status=status)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path)
    for row in align(parser.parse_args().events):
        print(json.dumps(row))
