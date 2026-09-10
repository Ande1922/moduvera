#!/usr/bin/env python3
"""Verify real Agent/canonical relations; require container error projection and expose request-bound duplicates."""
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
events = []
event_lines = {}
for line_number, line in enumerate((root / "servlet-agent.log").read_text().splitlines(), start=1):
    try:
        event = json.loads(line)
        events.append(event)
        event_lines[id(event)] = line_number
    except ValueError:
        pass
canonical = [event for event in events if event.get("log", {}).get("logger") == "http.request"]
assert canonical, "no canonical output"
by_span = {span["spanId"]: span for span in spans if span["kind"] == "SERVER"}
known_trace = "12345678901234567890123456789012"
unsampled = []
for event in canonical:
    assert event["log"]["level"] == "INFO"
    assert event["duration_ms"] >= 0
    assert event["correlation_id"]
    assert len(event["trace_id"]) == 32 and len(event["span_id"]) == 16
    server = by_span.get(event["span_id"])
    if server is None:
        assert event["trace_id"] == known_trace, "canonical has no matching exported server Span"
        unsampled.append(event)
    else:
        assert server["traceId"] == event["trace_id"]
        assert server["resource"]["telemetry.sdk.version"] == "1.65.0"
assert len(unsampled) == 1, "exactly one unsampled parent fixture must remain unexported"
parents = [span for span in spans if span["traceId"] == known_trace]
assert len(parents) == 1 and parents[0]["parentSpanId"] == "1234567890123456"
assert len({event["correlation_id"] for event in canonical}) == len(canonical), "duplicate canonical"
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == 0
assert status["postRequestsBySignal"]["metrics"] == 0
errors = [event for event in events if event.get("log", {}).get("level") == "ERROR"]
by_correlation = {event["correlation_id"]: event for event in canonical}
projection_fields = ("correlation_id", "trace_id", "span_id", "tenant_id", "actor_type", "actor_id",
                     "initiator_type", "initiator_id", "user_id")
error_rows = []
for event in errors:
    assert event.get("correlation_id"), "container ERROR is missing request correlation"
    completion = by_correlation[event["correlation_id"]]
    for field in ("correlation_id", "trace_id", "span_id"):
        assert event.get(field) == completion.get(field), f"ERROR projection mismatch: {field}"
    path = completion["url"]["path"]
    assert path in ("/fixture/async-error", "/fixture/error", "/managed/error", "/excluded/missing-context",
                    "/fixture/application-io", "/fixture/wrapped-application-io")
    # ManagedController is PLATFORM; ExcludedController bypasses the identity snapshot producer.
    # BasicErrorController later adds tenant identity, so canonical is not an emission-time identity oracle.
    expected_identity = ({"actor_type": "USER", "actor_id": "alice", "initiator_type": "USER",
                          "initiator_id": "alice", "user_id": "alice"} if path == "/managed/error" else {})
    for field in projection_fields[3:]:
        assert event.get(field) == expected_identity.get(field), f"ERROR emission-time identity mismatch: {field}"
        if field not in expected_identity:
            assert field not in event, f"unknown ERROR identity must be omitted: {field}"
    assert completion["event"]["outcome"] == "failure"
    assert event["error"]["code"] and event["error"]["stack_trace"]
    error_rows.append({"line": event_lines[id(event)], "canonicalLine": event_lines[id(completion)],
                       "timestamp": event["@timestamp"], "logger": event["log"]["logger"],
                       "message": event["message"], "errorCode": event["error"]["code"],
                       "canonicalPath": path,
                       "canonicalIdentity": {field: completion[field] for field in projection_fields[3:]
                                             if field in completion},
                       **{field: event.get(field) for field in projection_fields}})
original_errors = [row for row in error_rows if row["canonicalPath"] not in
                   ("/fixture/application-io", "/fixture/wrapped-application-io")]
assert len(original_errors) == 6, "fixture must retain all six original container ERROR events"
assert len(errors) == 8, "six original errors plus two application-I/O regression errors expected"
for path in ("/fixture/application-io", "/fixture/wrapped-application-io"):
    matches = [event for event in canonical if event["url"]["path"] == path]
    assert len(matches) == 1
    assert matches[0]["http"]["response"]["status_code"] == 500
duplicates = {correlation: sum(row["correlation_id"] == correlation for row in error_rows)
              for correlation in by_correlation
              if sum(row["correlation_id"] == correlation for row in error_rows) > 1}
print(json.dumps({"canonical": len(canonical), "exportedServerSpans": len(by_span),
                  "unsampled": len(unsampled), "sensitiveMatches": [], "errors": error_rows,
                  "requestBoundDuplicateErrors": duplicates,
                  "errorOwnershipComplete": not duplicates}, ensure_ascii=False, indent=2))
