#!/usr/bin/env python3
"""Validate real formatter stdout and Agent span evidence without third-party packages."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from typing import Any


SPAN_ID = re.compile(r"[0-9a-f]{16}")
SAMPLED_TRACE_ID = "11111111111111111111111111111111"
UNSAMPLED_TRACE_ID = "33333333333333333333333333333333"
REQUIRED_ROOTS = {"@timestamp", "log", "process", "service", "message", "ecs"}
TRUSTED_FIELDS = {
    "correlation_id": "fixture-correlation",
    "tenant_id": "fixture-tenant",
    "actor_type": "USER",
    "actor_id": "fixture-user",
    "initiator_type": "USER",
    "initiator_id": "fixture-user",
    "user_id": "fixture-user",
}


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise AssertionError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json_lines(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line, object_pairs_hook=unique_object)
        except (AssertionError, json.JSONDecodeError) as error:
            raise AssertionError(f"invalid ECS JSON at {path}:{line_number}: {error}") from error
        if not isinstance(value, dict):
            raise AssertionError(f"ECS line {line_number} is not an object")
        records.append(value)
    if not records:
        raise AssertionError("logging fixture produced no stdout records")
    return records


def one_message(records: list[dict[str, Any]], message: str) -> dict[str, Any]:
    matches = [record for record in records if record.get("message") == message]
    if len(matches) != 1:
        raise AssertionError(f"expected one message {message!r}, got {len(matches)}")
    return matches[0]


def assert_base_schema(record: dict[str, Any]) -> None:
    missing = REQUIRED_ROOTS - record.keys()
    assert not missing, f"missing required ECS roots: {sorted(missing)}"
    assert isinstance(record["@timestamp"], str) and record["@timestamp"].endswith("Z")
    assert isinstance(record["log"].get("level"), str)
    assert isinstance(record["log"].get("logger"), str)
    assert isinstance(record["process"].get("pid"), int)
    assert isinstance(record["process"].get("thread", {}).get("name"), str)
    assert record["service"] == {
        "name": "logging-fixture",
        "version": "0.1.0-verification",
        "environment": "verification",
    }
    assert record["ecs"] == {"version": "8.11"}
    assert isinstance(record["message"], str) and "\n" not in record["message"]


def assert_trace(record: dict[str, Any], trace_id: str) -> None:
    assert record.get("trace_id") == trace_id
    assert isinstance(record.get("span_id"), str) and SPAN_ID.fullmatch(record["span_id"])
    assert record["span_id"] != "0" * 16


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stdout", type=Path, required=True)
    parser.add_argument("--stderr", type=Path, required=True)
    parser.add_argument("--spans", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--sentinels", type=Path, required=True)
    args = parser.parse_args()

    sentinels = json.loads(args.sentinels.read_text(encoding="utf-8"))
    stdout_text = args.stdout.read_text(encoding="utf-8")
    stderr_text = args.stderr.read_text(encoding="utf-8")
    for label, sentinel in sentinels.items():
        assert sentinel not in stdout_text, f"sensitive {label} sentinel leaked to stdout"
        assert sentinel not in stderr_text, f"sensitive {label} sentinel leaked to stderr"

    records = read_json_lines(args.stdout)
    for record in records:
        assert_base_schema(record)

    ready = one_message(records, "governed logging fixture ready")
    assert not ({"trace_id", "span_id"} & ready.keys())
    assert not (TRUSTED_FIELDS.keys() & ready.keys())

    info = one_message(records, "fixture ordinary secret=[REDACTED]")
    assert info["log"]["level"] == "INFO"
    assert info["event"] == {"action": "fixture_observed"}
    assert info["order_id"] == "fixture-order-info"
    assert isinstance(info["duration_ms"], float) and info["duration_ms"] == 12.5
    assert isinstance(info["retry"]["attempt"], int) and not isinstance(info["retry"]["attempt"], bool)
    assert info["retry"]["attempt"] == 2
    assert_trace(info, SAMPLED_TRACE_ID)
    for field, expected in TRUSTED_FIELDS.items():
        assert info.get(field) == expected, f"unexpected trusted {field}"

    error = one_message(records, "fixture final failure, credential=[REDACTED]")
    assert error["log"]["level"] == "ERROR"
    assert error["order_id"] == "fixture-order-error"
    assert error["url"] == {"full": "https://example.test/orders"}
    assert_trace(error, UNSAMPLED_TRACE_ID)
    for field, expected in TRUSTED_FIELDS.items():
        assert error.get(field) == expected, f"unexpected trusted {field}"
    assert error["error"]["code"] == "DEP_DATABASE_UNAVAILABLE"
    assert error["error"]["type"] == "java.sql.SQLException"
    assert error["error"]["message"] == "Failure of type java.sql.SQLException"
    assert "java.sql.SQLException" in error["error"]["stack_trace"]
    assert "Caused by: java.net.http.HttpTimeoutException" in error["error"]["stack_trace"]
    assert set(error["error"]) == {"code", "type", "message", "stack_trace"}

    spans = [json.loads(line) for line in args.spans.read_text(encoding="utf-8").splitlines() if line.strip()]
    fixture_spans = [span for span in spans if span.get("resource", {}).get("service.name") == "logging-fixture"]
    assert len(fixture_spans) == 1, f"logging operations created extra spans: {len(fixture_spans)}"
    sampled = fixture_spans[0]
    assert sampled.get("kind") == "SERVER"
    assert sampled.get("traceId") == SAMPLED_TRACE_ID
    assert sampled.get("spanId") == info["span_id"]
    assert not any(span.get("traceId") == UNSAMPLED_TRACE_ID for span in spans)

    status = json.loads(args.status.read_text(encoding="utf-8"))
    assert status["sensitiveMatches"] == []
    assert "application/x-protobuf" in status["contentTypes"]
    print(
        "Governed logging fixture: PASS "
        f"({len(records)} ECS lines; sampled/unsampled IDs; one Agent SERVER span; no leaks)"
    )


if __name__ == "__main__":
    main()
