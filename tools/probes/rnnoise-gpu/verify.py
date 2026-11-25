"""Check the returned float PCM and each active frame's 22 gains + VAD probability."""
import argparse
import array
import math
import sys
from pathlib import Path


def floats(path):
    data = array.array("f")
    with path.open("rb") as source:
        data.fromfile(source, path.stat().st_size // 4)
    if sys.byteorder != "little":
        data.byteswap()
    return data


def compare(directory, kind):
    cpu, gpu = (floats(directory / f"{name}.{kind}") for name in ("cpu", "gpu"))
    if not cpu or len(cpu) != len(gpu):
        raise ValueError(f"{kind}: empty or unequal outputs")
    peak = energy = error = 0.0
    for a, b in zip(cpu, gpu):
        if not math.isfinite(a) or not math.isfinite(b):
            raise ValueError("non-finite prediction or sample")
        peak = max(peak, abs(a - b))
        error += (a - b) ** 2
        energy += a * a
    if not energy:
        raise ValueError(f"{kind}: silent or zero reference")
    snr = math.inf if not error else 10 * math.log10(energy / error)
    print(f"{kind} values={len(cpu)} peak_error={peak:.8g} agreement_snr_db={snr:.2f}")
    return peak, snr


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    directory = parser.parse_args().directory
    _, snr = compare(directory, "f32")
    peak, _ = compare(directory, "scores")
    if snr < 60 or peak > 0.001:
        sys.exit("CPU/GPU agreement outside probe tolerance")
