#!/usr/bin/env python3
"""Run a command in its own process group under a finite wall-clock deadline."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time


POLL_SECONDS = 0.05
TERM_GRACE_SECONDS = 0.5


def stop_process_group(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + TERM_GRACE_SECONDS
    while process.poll() is None and time.monotonic() < deadline:
        time.sleep(POLL_SECONDS)
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait()


def main(arguments: list[str]) -> int:
    if len(arguments) < 3 or arguments[1] != "--":
        print("usage: bounded_process.py POSITIVE_SECONDS -- COMMAND [ARG ...]", file=sys.stderr)
        return 64
    try:
        timeout_seconds = int(arguments[0], 10)
    except ValueError:
        return 64
    if timeout_seconds <= 0:
        return 64

    interrupted_by: int | None = None

    def record_signal(signal_number: int, _frame: object) -> None:
        nonlocal interrupted_by
        interrupted_by = signal_number

    signal.signal(signal.SIGINT, record_signal)
    signal.signal(signal.SIGTERM, record_signal)
    process = subprocess.Popen(arguments[2:], start_new_session=True)
    deadline = time.monotonic() + timeout_seconds
    while process.poll() is None:
        if interrupted_by is not None:
            stop_process_group(process)
            return 128 + interrupted_by
        if time.monotonic() >= deadline:
            print(
                f"Command exceeded {timeout_seconds}s wall-clock deadline: {arguments[2]}",
                file=sys.stderr,
            )
            stop_process_group(process)
            return 124
        time.sleep(POLL_SECONDS)
    return process.returncode


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
