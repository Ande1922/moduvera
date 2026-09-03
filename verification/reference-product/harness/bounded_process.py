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


def process_group_exists(process_group: int) -> bool:
    try:
        os.killpg(process_group, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def stop_process_group(process: subprocess.Popen[bytes]) -> bool:
    process_group = process.pid
    if process_group_exists(process_group):
        try:
            os.killpg(process_group, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + TERM_GRACE_SECONDS
    while process_group_exists(process_group) and time.monotonic() < deadline:
        process.poll()
        time.sleep(POLL_SECONDS)
    if process_group_exists(process_group):
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        print(
            f"Unable to reap command leader pid {process.pid} after escalation",
            file=sys.stderr,
        )
    group_deadline = time.monotonic() + 1
    while process_group_exists(process_group) and time.monotonic() < group_deadline:
        time.sleep(POLL_SECONDS)
    return not process_group_exists(process_group)


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
    return_code = process.returncode
    group_drained = True
    if process_group_exists(process.pid):
        group_drained = stop_process_group(process)
    if interrupted_by is not None:
        return 128 + interrupted_by
    if not group_drained:
        print(
            f"Command leader exited but process group {process.pid} could not be drained",
            file=sys.stderr,
        )
        return 70
    return return_code


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
