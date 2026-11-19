import json
import tempfile
import unittest
from pathlib import Path
from diagnostics import summarize


class DiagnosticsTest(unittest.TestCase):
    def test_cumulative_reports_are_not_added_together(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            events = [dict(event='run_started')]
            for n in [1, 2]:
                events.append(dict(event='performance', schema=1, cumulative=True, run='r1',
                                   final=n == 2, sync_calls=n, sync_wall_ns=n * 1000000,
                                   sync_cpu_ns=n * 1000, sync_max_wall_ns=1000000))
            path.write_text('\n'.join(map(json.dumps, events)))
            run = summarize(path)['runs'][0]
            self.assertTrue(run['complete'])
            self.assertEqual(run['stages'][0]['calls'], 2)
            self.assertEqual(run['stages'][0]['wall_ms'], 2)

    def test_legacy_log_has_no_fabricated_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            path.write_text('{"event":"telemetry"}\n')
            self.assertEqual(summarize(path)['runs'], [])
