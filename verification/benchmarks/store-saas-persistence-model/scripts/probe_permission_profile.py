#!/usr/bin/env python3
"""Run host-side positive/negative probes for one prepared candidate permission profile."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def config_args(commands: dict[str, object]) -> list[str]:
    initial = commands["initial"]
    if not isinstance(initial, list):
        raise ValueError("commands.initial is not an argv array")
    result = []
    index = 0
    while index < len(initial):
        if initial[index] == "-c":
            if index + 1 >= len(initial):
                raise ValueError("dangling -c in initial argv")
            result.extend(("-c", initial[index + 1]))
            index += 2
        else:
            index += 1
    return result


def execute(
    base: list[str],
    command: list[str],
    *,
    environment: dict[str, str] | None = None,
) -> tuple[int, str]:
    completed = subprocess.run(
        [*base, *command],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
        timeout=15,
        check=False,
    )
    output = (completed.stdout + completed.stderr).strip()
    return completed.returncode, output[:500]


def execute_plain(command: list[str]) -> tuple[int, str]:
    completed = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=15,
        check=False,
    )
    return completed.returncode, (completed.stdout + completed.stderr).strip()[:500]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-root", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--maven-home", type=Path, required=True)
    parser.add_argument("--memory-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--attest-outside-codex-sandbox", action="store_true")
    args = parser.parse_args()
    if not args.attest_outside_codex_sandbox:
        raise SystemExit("refusing an un-attested nested-sandbox calibration")

    run_root = args.run_root.resolve()
    candidate = run_root / "candidate-workspace"
    profile = run_root / "control" / "permission-profile.toml"
    commands_path = run_root / "control" / "commands.json"
    commands = json.loads(commands_path.read_text(encoding="utf-8"))
    codex = shutil.which("codex")
    if codex is None:
        raise SystemExit("codex executable is not on the host PATH")
    base = [
        codex,
        "sandbox",
        "-P",
        "pilot_candidate",
        "-C",
        str(candidate),
        *config_args(commands),
    ]
    observations: dict[str, dict[str, object]] = {}
    checks: dict[str, bool] = {}

    def probe(name: str, command: list[str], expected_success: bool, environment: dict[str, str] | None = None, marker: str | None = None) -> None:
        code, output = execute(base, command, environment=environment)
        passed = (code == 0) if expected_success else (code != 0)
        if marker is not None:
            passed = passed and marker in output
        checks[name] = passed
        observations[name] = {"exit_code": code, "expected_success": expected_success, "output": output}

    java_probe = candidate / "src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate/.permission-probe"
    xml_probe = candidate / "src/main/resources/io/github/ande1922/moduvera/benchmark/store/candidate/.permission-probe"
    root_probe = candidate / ".out-of-scope-permission-probe"
    try:
        probe("seed_read_allowed", ["/usr/bin/head", "-c", "1", "TASK.md"], True)
        probe("candidate_java_write_allowed", ["/usr/bin/touch", str(java_probe)], True)
        probe("candidate_xml_write_allowed", ["/usr/bin/touch", str(xml_probe)], True)
        probe("frozen_write_denied", ["/usr/bin/touch", str(root_probe)], False)
        probe(
            "repository_read_denied",
            ["/usr/bin/head", "-c", "1", str(args.repository_root.resolve() / "verification/benchmarks/store-saas-persistence-model/PROTOCOL.md")],
            False,
        )
        probe(
            "evaluator_read_denied",
            ["/usr/bin/head", "-c", "1", str(args.repository_root.resolve() / "verification/benchmarks/store-saas-persistence-model/pilot/t01/evaluator/scripts/scan_sql_scope.py")],
            False,
        )
        probe("memory_read_denied", ["/usr/bin/head", "-c", "1", str(args.memory_path.resolve())], False)
        control_code, control_output = execute_plain(["/usr/bin/nc", "-z", "-w", "3", "1.1.1.1", "443"])
        checks["network_control_available"] = control_code == 0
        observations["network_control_available"] = {
            "exit_code": control_code,
            "expected_success": True,
            "output": control_output,
        }
        probe("network_denied", ["/usr/bin/nc", "-z", "-w", "3", "1.1.1.1", "443"], False)
        probe("java_26_effective", [str(args.jdk_home.resolve() / "bin/java"), "-version"], True, marker='version "26')
        environment = {
            "JAVA_HOME": str(args.jdk_home.resolve()),
            "PATH": f"{args.jdk_home.resolve()}/bin:{args.maven_home.resolve()}/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        }
        probe(
            "maven_java_26_effective",
            [str(args.maven_home.resolve() / "bin/mvn"), "-version"],
            True,
            environment=environment,
            marker="Java version: 26",
        )
    finally:
        for path in (java_probe, xml_probe, root_probe):
            if path.is_file() and not path.is_symlink():
                path.unlink()

    report = {
        "schema_version": "0.1",
        "host_context": "outside-codex-sandbox",
        "candidate_root": str(candidate),
        "profile_sha256": sha256(profile),
        "commands_sha256": sha256(commands_path),
        "checks": checks,
        "observations": observations,
        "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS" if all(checks.values()) else "FAIL", "checks": checks}, sort_keys=True))
    return 0 if all(checks.values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
