import unittest

from performance import summarize


class SyncSummaryTest(unittest.TestCase):
    def rows(self):
        common = dict(side="phone", session="session", attempt="attempt", process_cpu_ms=10,
                      confirmed_segments=1, resumed_segments=0, full_offset_segments=0,
                      battery_status=3, battery_plugged=0)
        return [
            dict(common, elapsed_ms=0, payload_bytes=0, event="start",
                 battery_percent=50, charge_counter_uah_reported=50000),
            dict(common, elapsed_ms=2000, payload_bytes=2048, event="end", outcome="completed",
                 battery_percent=49, charge_counter_uah_reported=49990),
        ]

    def test_complete_attempt(self):
        result = summarize(self.rows())
        self.assertEqual(result["payload_kib_per_s"], 1)
        self.assertEqual(result["device_drop_percentage_points"], 1)
        self.assertEqual(result["device_drop_uah_reported"], 10)

    def test_powered_sample_suppresses_discharge(self):
        rows = self.rows()
        rows.append(dict(rows[0], event="sample", battery_plugged=2, elapsed_ms=500))
        self.assertIsNone(summarize(rows)["device_drop_uah_reported"])

    def test_incomplete_or_missing_counter(self):
        rows = self.rows()
        del rows[1]["charge_counter_uah_reported"]
        self.assertIsNone(summarize(rows)["device_drop_uah_reported"])
        result = summarize(rows[:1])
        self.assertFalse(result["complete"])
        self.assertIsNone(result["device_drop_percentage_points"])
