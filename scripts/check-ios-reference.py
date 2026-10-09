#!/usr/bin/env python3
"""Detect unreviewed iOS reference changes; this is not a feature-parity score."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("ios_root", type=Path)
args = parser.parse_args()
manifest = json.loads((Path(__file__).resolve().parents[1] / "validation/ios-reference-fingerprints.json").read_text())
changed = []
for name, expected in manifest["files"].items():
    source = args.ios_root / name
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        changed.append(name)
if changed:
    print("iOS reference changed: review these files and update Android coverage before refreshing fingerprints.")
    print("\n".join(changed))
    raise SystemExit(1)
print(f"{len(manifest['files'])} iOS reference files match the reviewed snapshot. Feature parity still requires functional and visual checks.")
