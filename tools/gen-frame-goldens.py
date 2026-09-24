#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate BLE frame golden vectors using the reference Python encoder.

This script is the source of truth for `core:protocol`'s cross-language golden
frame test. It loads `src/openflight/ble/protocol.py` from a checkout of the
reference `jake-fishtech/openflight` repo (pinned commit `b053194`) *by file
path*, so it never imports the `openflight.ble` package (which pulls in the
BLE publisher and hardware dependencies we don't want as a build dependency
here).

It re-serializes the shared contract fixture (`shot_v1.json`) as compact,
sorted-key JSON -- byte-identical to what `protocol.encode_shot_event` would
produce for that same event -- then fragments it into 20-byte BLE frames with
`protocol.fragment_payload`, and prints the frames as a Kotlin `listOf(...)`
of hex strings ready to paste into a commonTest golden-data file.

Usage (from a checkout of the reference repo, or pointing at one):

    uv run --no-project python tools/gen-frame-goldens.py \\
        --protocol /path/to/openflight/src/openflight/ble/protocol.py \\
        --fixture /path/to/openflight/ios/OpenFlightTests/Fixtures/shot_v1.json

Or, if this script and the reference clone share a scratch dir:

    uv run --no-project python tools/gen-frame-goldens.py \\
        --protocol "$REF/src/openflight/ble/protocol.py" \\
        --fixture "$REF/ios/OpenFlightTests/Fixtures/shot_v1.json"
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType


def load_protocol(path: Path) -> ModuleType:
    """Load protocol.py by file path, not by importing the `openflight` package."""
    spec = importlib.util.spec_from_file_location("openflight_ble_protocol", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"could not load protocol module from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--protocol",
        required=True,
        type=Path,
        help="path to the reference repo's src/openflight/ble/protocol.py",
    )
    parser.add_argument(
        "--fixture",
        required=True,
        type=Path,
        help="path to the reference repo's ios/OpenFlightTests/Fixtures/shot_v1.json",
    )
    parser.add_argument(
        "--sequence",
        type=lambda value: int(value, 0),
        default=0x0102,
        help="BLE message sequence to fragment with (default 0x0102)",
    )
    args = parser.parse_args()

    protocol = load_protocol(args.protocol)
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))

    # This mirrors protocol.encode_shot_event's own serialization exactly:
    # compact separators, sorted keys, ASCII-only. The fixture's *content* is
    # asserted (by the Python test suite) to equal what build_shot_event
    # produces, so re-dumping it this way reproduces the same bytes.
    payload = json.dumps(
        fixture,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")

    frames = protocol.fragment_payload(payload, sequence=args.sequence)

    print(f"// sequence = 0x{args.sequence:04X}", file=sys.stderr)
    print(f"// payload hex = {payload.hex()}", file=sys.stderr)
    print(f"// payload bytes = {len(payload)}", file=sys.stderr)
    print(f"// frame count = {len(frames)}", file=sys.stderr)

    print("listOf(")
    for frame in frames:
        print(f'    "{frame.hex()}",')
    print(")")


if __name__ == "__main__":
    main()
