import tempfile
import unittest
import json
import hashlib
from pathlib import Path
from audit import audit, inspect_adts

class AuditTest(unittest.TestCase):
    def test_variable_rates_and_inconsistent_segment(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            file = self.fixture(root)
            original = file.read_bytes()
            for rate, index in [(16000, 8), (24000, 6), (48000, 3)]:
                frame = bytearray(original)
                frame[2] = 0x40 | (index << 2)
                file.write_bytes(frame)
                result = inspect_adts(file)
                self.assertEqual(result['sample_rate'], rate)
                self.assertEqual(result['encoded_seconds'], round(1024 / rate, 3))
            file.write_bytes(original + frame)
            with self.assertRaisesRegex(ValueError, 'sample rate changed'):
                inspect_adts(file)

    def test_rate_must_match_segment_event(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            log = root / 'events.jsonl'
            event = json.loads(log.read_text())
            event['sample_rate'] = 48000
            log.write_text(json.dumps(event))
            self.assertTrue(any('sample rate differs' in e for e in audit(root)['errors']))

    def fixture(self, root):
        payload = b'\x00' * 20
        size = 7 + len(payload)
        frame = bytes([255, 241, 96, 64 | (size >> 11), size >> 3, ((size & 7) << 5) | 31, 252]) + payload
        file = root / 'run-0001.aac'
        file.write_bytes(frame)
        metadata = {'event': 'segment_closed', 'file': file.name, 'bytes': len(frame), 'sha256': hashlib.sha256(frame).hexdigest()}
        (root / 'events.jsonl').write_text(json.dumps(metadata) + '\n')
        return file
    def test_finalized_structure_and_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            result = audit(root)
            self.assertTrue(result['structure_and_hash_ok'])
            self.assertEqual(result['files'][0]['frames'], 1)
    def test_corruption_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            file = self.fixture(root)
            file.write_bytes(file.read_bytes()[:-1])
            result = audit(root)
            self.assertFalse(result['structure_and_hash_ok'])
            self.assertEqual(len(result['errors']), 2)
    def test_missing_file_is_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root).unlink()
            self.assertFalse(audit(root)['structure_and_hash_ok'])
    def test_unfinalized_segment_is_warning(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            (root / 'events.jsonl').unlink()
            result = audit(root)
            self.assertFalse(result['structure_and_hash_ok'])
            self.assertTrue(result['warnings'])

if __name__ == '__main__':
    unittest.main()
