"""Summarize exported sync.jsonl and sync.previous.jsonl files from either device."""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def summarize(rows):
    rows = sorted(rows, key=lambda r: r["elapsed_ms"])
    first, last = rows[0], rows[-1]
    complete = first["event"] == "start" and last["event"] == "end"
    elapsed = last["elapsed_ms"]
    summary = {key: last[key] for key in (
        "side", "session", "attempt", "elapsed_ms", "process_cpu_ms", "payload_bytes",
        "confirmed_segments", "resumed_segments", "full_offset_segments"
    )}
    summary.update(
        complete=complete, outcome=last.get("outcome", "incomplete"),
        payload_kib_per_s=round(last["payload_bytes"] * 1000 / (1024 * elapsed), 2)
        if complete and elapsed else None,
        phase_wall_ms=last.get("phase_wall_ms"),
        phase_thread_cpu_ns=last.get("phase_thread_cpu_ns"),
        battery_percent_start=first.get("battery_percent"), battery_percent_last=last.get("battery_percent"),
    )
    discharging = all(r.get("battery_plugged") == 0 and r.get("battery_status") == 3 for r in rows)
    for column, field in (("device_drop_percentage_points", "battery_percent"),
                          ("device_drop_uah_reported", "charge_counter_uah_reported")):
        start, end = first.get(field), last.get(field)
        summary[column] = (start - end if complete and discharging and
                           start is not None and end is not None and start >= end >= 0 else None)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    groups = defaultdict(list)
    for path in args.files:
        with path.open(encoding="utf-8") as stream:
            for n, line in enumerate(stream, 1):
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    print(f"Skipping invalid record at {path}:{n}", file=sys.stderr)
                    continue
                groups[row["side"], row["session"], row["attempt"]].append(row)
    for key in sorted(groups):
        print(json.dumps(summarize(groups[key]), ensure_ascii=False))


if __name__ == "__main__":
    main()
