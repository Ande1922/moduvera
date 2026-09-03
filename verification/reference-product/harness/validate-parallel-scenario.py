#!/usr/bin/env python3
"""Validate successful, isolated evidence from two concurrent reference topologies."""

from __future__ import annotations

import json
import pathlib
import sys


def fail(message: str) -> None:
    raise SystemExit("Parallel reference scenario evidence is invalid: " + message)


def load_manifest(path: str, expected_topology: str) -> dict:
    manifest = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    if manifest.get("topology") != expected_topology:
        fail(f"{path} does not describe {expected_topology}")
    resources = manifest.get("resources")
    if not isinstance(resources, dict):
        fail(f"{path} has no resource identities")
    for key in ("runId", "composeProject", "dataNamespace", "tempDirectory"):
        if not resources.get(key):
            fail(f"{path} has no resources.{key}")
    topics = resources.get("topics")
    if not isinstance(topics, dict) or not topics:
        fail(f"{path} has no resources.topics object")
    return manifest


def host_ports(manifest: dict) -> set[int]:
    ports = manifest["ports"]
    result = {int(ports["postgres"]), int(ports["kafka"])}
    for app_ports in ports["apps"].values():
        result.add(int(app_ports["application"]))
        if "debug" in app_ports:
            result.add(int(app_ports["debug"]))
    return result


def assert_public_contract(log_path: str, topology: str) -> None:
    log = pathlib.Path(log_path).read_text(encoding="utf-8")
    if f"reference acceptance: PASS (topology={topology};" not in log:
        fail(f"{topology} public HTTP contract PASS is absent from {log_path}")
    if f"Reference product verification: PASS (topology={topology}; public contract + Kafka recovery)" not in log:
        fail(f"{topology} final topology PASS is absent from {log_path}")


def main(arguments: list[str]) -> int:
    if len(arguments) != 4:
        fail("expected MICRO_MANIFEST MICRO_LOG MONOLITH_MANIFEST MONOLITH_LOG")
    micro_manifest_path, micro_log, monolith_manifest_path, monolith_log = arguments
    micro = load_manifest(micro_manifest_path, "microservices")
    monolith = load_manifest(monolith_manifest_path, "business-core-monolith")

    if micro["runSlot"] == monolith["runSlot"]:
        fail("run slots are not distinct")
    overlapping_ports = sorted(host_ports(micro).intersection(host_ports(monolith)))
    if overlapping_ports:
        fail(f"host ports overlap: {overlapping_ports}")

    micro_resources = micro["resources"]
    monolith_resources = monolith["resources"]
    for key in ("runId", "composeProject", "dataNamespace", "tempDirectory"):
        if micro_resources[key] == monolith_resources[key]:
            fail(f"resources.{key} is not distinct")

    micro_topics = set(micro_resources["topics"].values())
    monolith_topics = set(monolith_resources["topics"].values())
    if len(micro_topics) != 2 or len(monolith_topics) != 2:
        fail("each topology must expose two distinct topic identities")
    if micro_topics.intersection(monolith_topics):
        fail("Kafka topic identities overlap")

    assert_public_contract(micro_log, "microservices")
    assert_public_contract(monolith_log, "business-core-monolith")

    print(
        json.dumps(
            {
                "status": "PASS",
                "microservices": {
                    "runSlot": micro["runSlot"],
                    "composeProject": micro_resources["composeProject"],
                    "topics": sorted(micro_topics),
                    "ports": sorted(host_ports(micro)),
                },
                "businessCoreMonolith": {
                    "runSlot": monolith["runSlot"],
                    "composeProject": monolith_resources["composeProject"],
                    "topics": sorted(monolith_topics),
                    "ports": sorted(host_ports(monolith)),
                },
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
