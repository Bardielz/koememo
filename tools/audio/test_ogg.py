import struct
import tempfile
import unittest
from pathlib import Path
from ogg_audit import crc, inspect_ogg

def page(packet, seq, flags, granule):
    lacing = bytes([255] * (len(packet) // 255) + [len(packet) % 255])
    data = bytearray(b'OggS\0' + bytes([flags]) + struct.pack('<QIII', granule, 123, seq, 0) + bytes([len(lacing)]) + lacing + packet)
    struct.pack_into('<I', data, 22, crc(data))
    return data

class OggTest(unittest.TestCase):
    def fixture(self, path):
        head = b'OpusHead' + bytes([1, 1]) + struct.pack('<HIhB', 312, 16000, 0, 0)
        tags = b'OpusTags' + struct.pack('<II', 0, 0)
        path.write_bytes(page(head, 0, 2, 0) + page(tags, 1, 0, 0) + page(b'\xf8\xff\xfe', 2, 4, 960))
    def test_duration_accounts_for_preskip(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'audio.ogg'; self.fixture(p)
            result = inspect_ogg(p)
            self.assertEqual(result['frames'], 1)
            self.assertEqual(result['encoded_seconds'], round((960-312)/48000, 3))
    def test_corrupt_or_incomplete_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'audio.ogg'; self.fixture(p)
            original = p.read_bytes()
            damaged = bytearray(original); damaged[-1] ^= 1; p.write_bytes(damaged)
            with self.assertRaisesRegex(ValueError, 'CRC'): inspect_ogg(p)
            p.write_bytes(original[:-1])
            with self.assertRaises(ValueError): inspect_ogg(p)
