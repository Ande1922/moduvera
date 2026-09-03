#!/usr/bin/env python3
"""Run and supervise both reference topologies in isolated process groups."""

from __future__ import annotations

import argparse
import math
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import tempfile
import time


TOPOLOGIES = ("microservices", "business-core-monolith")


def parse_timeout(name: str, default: float) -> float:
    raw_timeout = os.environ.get(name, str(default))
    try:
        timeout = float(raw_timeout)
    except ValueError:
        raise SystemExit(
            f"Invalid {name}; expected a finite non-negative number"
        ) from None
    if not math.isfinite(timeout) or timeout < 0:
        raise SystemExit(
            f"Invalid {name}; expected a finite non-negative number"
        )
    return timeout


def create_evidence_directory() -> tuple[pathlib.Path, bool]:
    configured = os.environ.get("REFERENCE_PARALLEL_EVIDENCE_DIR")
    if configured:
        evidence = pathlib.Path(configured).expanduser()
        evidence.mkdir()
        return evidence.resolve(), False
    evidence = pathlib.Path(
        tempfile.mkdtemp(
            prefix="moduvera-reference-parallel.",
            dir=os.environ.get("TMPDIR"),
        )
    )
    return evidence.resolve(), True


def signal_group(process: subprocess.Popen[bytes], requested_signal: signal.Signals) -> None:
    try:
        os.killpg(process.pid, requested_signal)
    except ProcessLookupError:
        pass


def stop_process_groups(
    processes: dict[str, subprocess.Popen[bytes]], timeout: float
) -> None:
    for process in processes.values():
        signal_group(process, signal.SIGTERM)

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if all(process.poll() is not None for process in processes.values()):
            break
        time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))

    for process in processes.values():
        # Signal the process group even when its leader already exited: descendants may remain.
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            continue
        signal_group(process, signal.SIGKILL)

    for process in processes.values():
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            print(
                f"Parallel supervisor could not reap topology wrapper pid {process.pid}",
                file=sys.stderr,
            )


def tail(path: pathlib.Path, line_count: int = 80) -> str:
    try:
        return "".join(path.read_text(encoding="utf-8", errors="replace").splitlines(True)[-line_count:])
    except OSError as failure:
        return f"Unable to read {path}: {failure}\n"


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner", required=True)
    parser.add_argument("--validator", required=True)
    parser.add_argument("microservices_slot")
    parser.add_argument("monolith_slot")
    options = parser.parse_args(arguments)
    runner_stop_timeout = parse_timeout("REFERENCE_APP_STOP_TIMEOUT_SECONDS", 5)
    compose_down_timeout = parse_timeout("REFERENCE_COMPOSE_DOWN_TIMEOUT_SECONDS", 10)
    timeout = parse_timeout(
        "REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS",
        runner_stop_timeout + compose_down_timeout + 5,
    )
    validator_timeout = parse_timeout("REFERENCE_PARALLEL_VALIDATOR_TIMEOUT_SECONDS", 10)
    evidence, evidence_is_temporary = create_evidence_directory()
    keep_temporary = os.environ.get("REFERENCE_KEEP_PARALLEL_EVIDENCE", "0") == "1"

    manifests = {
        "microservices": evidence / "microservices-manifest.json",
        "business-core-monolith": evidence / "business-core-monolith-manifest.json",
    }
    logs = {
        "microservices": evidence / "microservices.log",
        "business-core-monolith": evidence / "business-core-monolith.log",
    }
    slots = {
        "microservices": options.microservices_slot,
        "business-core-monolith": options.monolith_slot,
    }
    processes: dict[str, subprocess.Popen[bytes]] = {}
    log_handles: list[object] = []
    interrupted_signal = 0
    success = False
    validator_process: subprocess.Popen[bytes] | None = None

    def record_signal(received: int, _frame: object) -> None:
        nonlocal interrupted_signal
        if not interrupted_signal:
            interrupted_signal = received

    old_handlers = {
        received: signal.signal(received, record_signal)
        for received in (signal.SIGINT, signal.SIGTERM)
    }
    try:
        print(
            "Starting parallel reference scenario: "
            f"microservices slot {slots['microservices']}; "
            f"business-core-monolith slot {slots['business-core-monolith']}",
            flush=True,
        )
        for topology in TOPOLOGIES:
            log_handle = logs[topology].open("wb")
            log_handles.append(log_handle)
            child_environment = os.environ.copy()
            child_environment["RUN_SLOT"] = slots[topology]
            child_environment["REFERENCE_PORT_MANIFEST"] = str(manifests[topology])
            processes[topology] = subprocess.Popen(
                [options.runner, topology],
                env=child_environment,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )

        statuses: dict[str, int] = {}
        while len(statuses) < len(processes):
            for topology, process in processes.items():
                if topology not in statuses and (status := process.poll()) is not None:
                    statuses[topology] = status
            if interrupted_signal or any(status != 0 for status in statuses.values()):
                if interrupted_signal:
                    for process in processes.values():
                        signal_group(process, signal.Signals(interrupted_signal))
                stop_process_groups(processes, timeout)
                for topology, process in processes.items():
                    statuses[topology] = process.poll() if process.poll() is not None else -signal.SIGKILL
                break
            time.sleep(0.05)

        if interrupted_signal:
            print(
                f"Parallel reference scenario interrupted by signal {interrupted_signal}",
                file=sys.stderr,
            )
            return 128 + interrupted_signal

        if any(statuses.get(topology, 1) != 0 for topology in TOPOLOGIES):
            print(
                "Parallel reference scenario failed: "
                f"microservices={statuses.get('microservices')} "
                f"business-core-monolith={statuses.get('business-core-monolith')}",
                file=sys.stderr,
            )
            for topology in TOPOLOGIES:
                print(f"--- {topology} (last 80 lines)", file=sys.stderr)
                print(tail(logs[topology]), end="", file=sys.stderr)
            return 1

        for log_handle in log_handles:
            log_handle.flush()
        validator_process = subprocess.Popen(
            [
                sys.executable,
                options.validator,
                str(manifests["microservices"]),
                str(logs["microservices"]),
                str(manifests["business-core-monolith"]),
                str(logs["business-core-monolith"]),
            ],
            start_new_session=True,
        )
        validator_deadline = time.monotonic() + validator_timeout
        while (validator_status := validator_process.poll()) is None:
            if interrupted_signal:
                signal_group(validator_process, signal.Signals(interrupted_signal))
                stop_process_groups({"validator": validator_process}, timeout)
                print(
                    f"Parallel reference scenario interrupted by signal {interrupted_signal}",
                    file=sys.stderr,
                )
                return 128 + interrupted_signal
            if time.monotonic() >= validator_deadline:
                stop_process_groups({"validator": validator_process}, timeout)
                print(
                    f"Parallel evidence validator exceeded {validator_timeout:g} seconds",
                    file=sys.stderr,
                )
                return 124
            time.sleep(0.05)
        if interrupted_signal:
            print(
                f"Parallel reference scenario interrupted by signal {interrupted_signal}",
                file=sys.stderr,
            )
            return 128 + interrupted_signal
        if validator_status != 0:
            return validator_status

        success = True
        print(
            "Parallel reference product verification: PASS "
            f"(microservices slot {slots['microservices']}; "
            f"business-core-monolith slot {slots['business-core-monolith']})",
            flush=True,
        )
        return 0
    finally:
        if processes and (interrupted_signal or not success):
            stop_process_groups(processes, timeout)
        if validator_process is not None and validator_process.poll() is None:
            stop_process_groups({"validator": validator_process}, timeout)
        for log_handle in log_handles:
            log_handle.close()
        for received, old_handler in old_handlers.items():
            signal.signal(received, old_handler)
        if success and evidence_is_temporary and not keep_temporary:
            shutil.rmtree(evidence)
        else:
            print(f"Parallel reference evidence retained at {evidence}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
