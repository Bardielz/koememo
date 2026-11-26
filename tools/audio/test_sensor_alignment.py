import json
from pathlib import Path
import runpy
from tempfile import TemporaryDirectory
import unittest

align = runpy.run_path(str(Path(__file__).with_name("align-sensors.py")))["align"]


class SensorAlignmentTest(unittest.TestCase):
    def test_boot_clock_and_gate_replay_are_distinct_from_media_time(self):
        start = 123_000_000_000
        events = [
            dict(event="run_started", run="a", sample_rate=16000),
            dict(event="audio_configuration", audio_boottime_ns=start, audio_frame_position=0),
            dict(event="quiet_gate", action="pause", captured_samples=162048, held_samples=2048),
            dict(event="quiet_gate", action="resume", captured_samples=242048,
                 held_samples=80000, replay_samples=16000),
            dict(event="sensor_batch", run="a", samples=[
                [start + seconds * 10**9, 21, 3, 70] for seconds in [0, 10, 14, 15, 20]]),
            dict(event="audio_configuration", capture_end_samples=320000),
            dict(event="run_stopped"),
            dict(event="run_started", run="b", sample_rate=16000),
            dict(event="sensor_batch", run="b", samples=[[start, 1, 0, 0, 0, 0]]),
        ]
        with TemporaryDirectory() as tmp:
            path = Path(tmp, "events.jsonl")
            path.write_text("".join(json.dumps(e) + "\n" for e in events))
            result = list(align(path))
        self.assertEqual([r["media_ms"] for r in result], [0, None, 10000, 11000, None, None])
        self.assertEqual([r["status"] for r in result],
                         ["aligned", "omitted", "aligned", "aligned", "outside_audio", "no_audio_anchor"])
