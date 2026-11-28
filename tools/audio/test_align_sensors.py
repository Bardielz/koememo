import json
import runpy
import tempfile
import unittest
from pathlib import Path

align = runpy.run_path(str(Path(__file__).with_name('align-sensors.py')))['align']


class SensorTimelineTest(unittest.TestCase):
    def test_batched_samples_use_capture_time_and_report_quality_separately(self):
        # Samples can arrive together, after the audio writer has closed its second segment.
        events = [
            dict(event="run_started", run="r1", sample_rate=16000),
            dict(event="audio_configuration", audio_boottime_ns=1_000_000_000, audio_frame_position=0),
            dict(event="quiet_gate", action="pause", captured_samples=82048, held_samples=2048, replay_samples=0),
            dict(event="quiet_gate", action="resume", captured_samples=242048, held_samples=160000, replay_samples=16000),
            dict(event="segment_closed", file="first.ogg", playback_start_us=0, end_pts_us=4_000_000),
            dict(event="segment_closed", file="second.ogg", playback_start_us=4_000_000, end_pts_us=21_000_000),
            dict(event="audio_configuration", capture_end_samples=480000),
            dict(event="sensor_batch", run="r1", samples=[
                [5_000_000_000, 1, 3, 1.0, 2.0, 3.0],
                [11_000_000_000, 21, -1, 0],
                [16_000_000_000, 21, 3, 75],
                [31_000_000_000, 21, 0, None]]),
            dict(event="run_stopped"),
            dict(event="run_started", run="r2", sample_rate=16000),
            dict(event="sensor_batch", run="r2", samples=[[33_000_000_000, 21, 0, 90]]),
        ]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            path.write_text("\n".join(map(json.dumps, events)))
            motion, off_wrist, on_wrist, after, unanchored = align(path)
        self.assertEqual((motion['segment_file'], motion['segment_ms']), ("second.ogg", 0))
        self.assertEqual((off_wrist['status'], off_wrist['heart_rate_quality']), ("omitted", "no_contact"))
        self.assertIsNone(off_wrist['bpm'])
        self.assertIsNone(off_wrist['segment_file'])
        self.assertEqual((on_wrist['bpm'], on_wrist['segment_ms'], on_wrist['media_ms']), (75, 2000, 6000))
        self.assertEqual((after['status'], after['heart_rate_quality']), ("outside_audio", "unreliable"))
        self.assertEqual(unanchored['status'], "no_audio_anchor")
        self.assertIsNone(unanchored['bpm'])
