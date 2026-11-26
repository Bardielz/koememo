"""Prepare the v0.1 model as a resident SSBO and generate named offsets for the shader."""
import argparse
import re
import struct
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("upstream", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source = (args.upstream / "src/rnn_data.c").read_text()
weights = bytearray()
defines = []
for name, size, data in re.findall(r"static const rnn_weight (\w+)\[(\d+)\] = \{([^}]+)\}", source):
    values = [int(x) for x in re.findall(r"-?\d+", data)]
    assert len(values) == int(size)
    defines.append(f"#define {name.upper()} {len(weights)}")
    weights.extend(struct.pack(f"{len(values)}b", *values))
weights.extend(b"\0" * (-len(weights) % 4))
args.output.mkdir(parents=True, exist_ok=True)
(args.output / "weights.bin").write_bytes(weights)
shader = Path(__file__).with_name("network.comp").read_text()
(args.output / "network.glsl").write_text(shader.replace("// MODEL_OFFSETS", "\n".join(defines)))
