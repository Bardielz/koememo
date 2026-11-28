#!/usr/bin/env python3
"""Summarize cumulative performance events without converting CPU time into energy."""
import argparse
import json
from pathlib import Path


def battery_runs(path):
    """Report whole-device discharge only for bounded runs with unplugged observations."""
    results = []
    active = None
    samples = []
    with Path(path).open() as source:
        for line in source:
            event = json.loads(line)
            kind = event.get('event')
            if kind == 'run_started':
                active = {key: event.get(key) for key in (
                    'run', 'sample_rate', 'encoding', 'bitrate', 'quiet_gate', 'diagnostics_enabled')}
                active.update(closed_segments=0, complete=False)
                results.append(active)
                samples = []
            if active is None:
                continue
            if kind == 'segment_closed':
                active['closed_segments'] += 1
            if kind in ('run_started', 'telemetry', 'error', 'run_stopped'):
                samples.append(event)
            if kind not in ('run_stopped', 'error'):
                continue
            start, stop = samples[0], samples[-1]
            times = [s.get('elapsed_ms') for s in samples]
            active.update(complete=True, stop_reason=stop.get('reason'), samples=len(samples))
            if all(isinstance(t, int) for t in times) and all(b >= a for a, b in zip(times, times[1:])):
                duration = (times[-1] - times[0]) / 1000
                active.update(duration_s=duration, max_sample_gap_s=max(b-a for a, b in zip(times, times[1:])) / 1000)
                unplugged = all(s.get('battery_plugged') == 0 and s.get('battery_status') not in (2, 5) for s in samples)
                first, last = start.get('battery_percent'), stop.get('battery_percent')
                active['all_samples_unplugged'] = unplugged
                if unplugged and duration > 0 and all(isinstance(n, int) and 0 <= n <= 100 for n in (first, last)) and last <= first:
                    active.update(start_percent=first, end_percent=last,
                                  device_percentage_points_per_hour=(first-last)*3600/duration)
            active = None
    return {'runs': results, 'scope': 'Whole-device battery gauge at logged observations. Charging between observations is unknown.'}


def summarize(path):
    runs = {}
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        event = json.loads(line)
        if event.get('event') != 'performance':
            continue
        if event.get('schema') not in (1, 2) or event.get('cumulative') is not True:
            raise ValueError(f'Unsupported performance schema at line {number}')
        run = event['run']
        previous = runs.get(run)
        if previous and previous.get('final'):
            raise ValueError(f'Performance event after final report for {run}')
        runs[run] = event
    results = []
    for run, event in runs.items():
        stages = []
        for key, calls in event.items():
            if not key.endswith('_calls'):
                continue
            stage = key[:-6]
            samples = calls if event['schema'] == 1 else event[stage + '_samples']
            interval = 1 if event['schema'] == 1 else event[stage + '_sample_every']
            wall = event[stage + '_wall_ns']
            cpu = event[stage + '_cpu_ns']
            prefix = 'sampled_' if interval > 1 else ''
            stages.append({'stage': stage, 'calls': calls, 'samples': samples, 'sample_every': interval,
                           prefix + 'wall_ms': wall / 1e6,
                           prefix + 'thread_cpu_ms': cpu / 1e6,
                           prefix + 'mean_wall_ms': wall / samples / 1e6 if samples else None,
                           prefix + 'max_wall_ms': event[stage + '_max_wall_ns'] / 1e6})
        results.append({'run': run, 'complete': event.get('final', False), 'stages': stages})
    return {'runs': results, 'scope': 'Capture-thread observations; excludes codec process CPU and does not measure energy. Latest cumulative report per run only.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path)
    parser.add_argument('--battery', action='store_true', help='summarize observed recording runs and whole-device discharge')
    args = parser.parse_args()
    print(json.dumps((battery_runs if args.battery else summarize)(args.events), ensure_ascii=False, indent=2))
