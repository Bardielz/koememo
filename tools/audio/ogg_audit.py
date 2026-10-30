"""Structural validation for single-stream mono Ogg Opus; not a decoder."""
import struct

def _crc_entry(value):
    value <<= 24
    for _ in range(8):
        value = ((value << 1) ^ (0x04c11db7 if value & 0x80000000 else 0)) & 0xffffffff
    return value

_CRC_TABLE = [_crc_entry(i) for i in range(256)]

def crc(data):
    result = 0
    for byte in data:
        result = ((result << 8) & 0xffffffff) ^ _CRC_TABLE[((result >> 24) ^ byte) & 255]
    return result

def inspect_ogg(path):
    packets = 0
    sequence = 0
    serial = None
    pending = bytearray()
    pre_skip = None
    last_granule = 0
    ended = False
    with path.open('rb') as stream:
        while True:
            header = stream.read(27)
            if not header:
                break
            if ended or len(header) != 27 or header[:5] != b'OggS\x00':
                raise ValueError('invalid or truncated Ogg page')
            flags = header[5]
            granule, page_serial, seq, checksum = struct.unpack_from('<QIII', header, 6)
            if flags & ~7 or bool(flags & 1) != bool(pending):
                raise ValueError('invalid Ogg continuation')
            if seq != sequence or (sequence == 0 and not flags & 2) or (sequence > 0 and flags & 2):
                raise ValueError('Ogg sequence/BOS mismatch')
            if serial is not None and serial != page_serial:
                raise ValueError('unexpected chained Ogg stream')
            serial = page_serial
            sequence += 1
            lacing = stream.read(header[26])
            payload = stream.read(sum(lacing))
            if len(lacing) != header[26] or len(payload) != sum(lacing):
                raise ValueError('truncated Ogg payload')
            page = bytearray(header + lacing + payload)
            page[22:26] = b'\0' * 4
            if crc(page) != checksum:
                raise ValueError('Ogg CRC mismatch')
            offset = 0
            for length in lacing:
                pending.extend(payload[offset:offset + length]); offset += length
                if length < 255:
                    if packets == 0:
                        if len(pending) < 19 or pending[:8] != b'OpusHead' or pending[9] != 1:
                            raise ValueError('invalid mono OpusHead')
                        pre_skip = int.from_bytes(pending[10:12], 'little')
                    elif packets == 1 and pending[:8] != b'OpusTags':
                        raise ValueError('missing OpusTags')
                    elif not pending:
                        raise ValueError('empty Opus packet')
                    packets += 1
                    pending.clear()
            if granule != 0xffffffffffffffff:
                if granule < last_granule:
                    raise ValueError('non-monotonic Ogg granule')
                last_granule = granule
            ended = bool(flags & 4)
    if pending or packets <= 2 or not ended or pre_skip is None or last_granule < pre_skip:
        raise ValueError('incomplete or unfinalized Opus stream')
    return {'encoding': 'OPUS', 'sample_rate': 48000, 'pre_skip_samples': pre_skip,
            'frames': packets - 2, 'encoded_seconds': round((last_granule - pre_skip) / 48000, 3)}
