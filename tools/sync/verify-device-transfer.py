"""Check completed debug-app transfers on a connected phone and watch."""
import argparse
from pathlib import PurePosixPath
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--phone", required=True, help="Phone adb serial")
    parser.add_argument("--watch", required=True, help="Watch adb serial")
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()
    if args.samples < 1:
        parser.error("--samples must be positive")

    def read(serial, *command):
        return subprocess.check_output(
            [args.adb, "-s", serial, "shell", shlex.join(["run-as", "dev.watchmemory", *command])],
            text=True,
            timeout=60,
        )

    paths = sorted(
        PurePosixPath(name)
        for name in read(args.phone, "find", "files/audio", "-type", "f").splitlines()
        if name.endswith((".aac", ".ogg"))
    )
    if len(paths) < args.samples:
        raise SystemExit(f"Only {len(paths)} completed files found, need {args.samples}")
    # Sample across the library so that a single session need not dominate.
    for i in range(args.samples):
        path = paths[i * (len(paths) - 1) // max(1, args.samples - 1)]
        original = PurePosixPath("files/recordings", path.parent.name, path.name)
        phone_hash = read(args.phone, "sha256sum", str(path)).split()[0]
        watch_hash = read(args.watch, "sha256sum", str(original)).split()[0]
        if not re.fullmatch("[0-9a-f]{64}", phone_hash) or phone_hash != watch_hash:
            raise SystemExit(f"SHA-256 mismatch for {path.name}")
        receipts = ET.fromstring(read(args.watch, "cat", "shared_prefs/sync_receipts.xml"))
        if not any(
            row.get("name", "").endswith("/" + phone_hash) and row.get("value") == "true"
            for row in receipts.findall("boolean")
        ):
            raise SystemExit(f"No watch acknowledgement for {path.name}")
    print(f"Verified {args.samples} of {len(paths)} completed files against watch originals and receipts")


if __name__ == "__main__":
    main()
