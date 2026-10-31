#!/usr/bin/env python3
"""Summarize cumulative performance events without converting CPU time into energy."""
import argparse
import json
from pathlib import Path


def summarize(path):
    runs = {}
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        event = json.loads(line)
        if event.get('event') != 'performance':
            continue
        if event.get('schema') != 1 or event.get('cumulative') is not True:
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
            wall = event[stage + '_wall_ns']
            cpu = event[stage + '_cpu_ns']
            stages.append({'stage': stage, 'calls': calls, 'wall_ms': wall / 1e6,
                           'thread_cpu_ms': cpu / 1e6,
                           'mean_wall_ms': wall / calls / 1e6 if calls else None,
                           'max_wall_ms': event[stage + '_max_wall_ns'] / 1e6})
        results.append({'run': run, 'complete': event.get('final', False), 'stages': stages})
    return {'runs': results, 'scope': 'Capture-thread observations; excludes codec process CPU and does not measure energy. Latest cumulative report per run only.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.events), ensure_ascii=False, indent=2))
