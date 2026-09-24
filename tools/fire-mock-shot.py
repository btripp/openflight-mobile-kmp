#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fire simulated shots at a running OpenFlight mock server.

The mock server (`uv run openflight-server --mock` in an openflight checkout) only
produces shots when a Socket.IO client emits `simulate_shot`; there is no HTTP route.
Each simulated shot is then published on the SSE stream (`/api/shots/stream`) as an
`event: shot`, which is what the app's Wi-Fi transport consumes.

Usage:
    uv run --with "python-socketio[client]" tools/fire-mock-shot.py [-n 3] [--url http://localhost:8080]
"""

from __future__ import annotations

import argparse
import sys
import time

import socketio


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-n", "--count", type=int, default=1, help="number of shots to fire (default: 1)")
    parser.add_argument("--url", default="http://localhost:8080", help="server base URL (default: http://localhost:8080)")
    parser.add_argument("--interval", type=float, default=1.0, help="seconds between shots (default: 1.0)")
    args = parser.parse_args()

    sio = socketio.Client()
    try:
        sio.connect(args.url, wait_timeout=10)
    except socketio.exceptions.ConnectionError as exc:
        print(f"Could not connect to {args.url}: {exc}", file=sys.stderr)
        return 1

    try:
        for i in range(args.count):
            sio.emit("simulate_shot")
            print(f"simulate_shot {i + 1}/{args.count}")
            time.sleep(args.interval)
    finally:
        sio.disconnect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
