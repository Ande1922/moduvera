#!/usr/bin/env python3
"""Verify real Agent/canonical relations; verify Advice error ownership and report native container errors separately."""
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
advice_logger = "io.github.ande1922.moduvera.web.ApiExceptionHandler"
governed_errors = [event for event in errors if event["log"]["logger"] == advice_logger]
native_errors = [event for event in errors if event["log"]["logger"] != advice_logger]
assert all(event["log"]["logger"].startswith("org.apache.catalina.") for event in native_errors), \
    "unexpected ERROR owner outside the declared Advice/container boundary"
by_correlation = {event["correlation_id"]: event for event in canonical}
projection_fields = ("correlation_id", "trace_id", "span_id", "tenant_id", "actor_type", "actor_id",
                     "initiator_type", "initiator_id", "user_id")
error_rows = []
for event in governed_errors:
    assert event.get("correlation_id"), "Advice ERROR is missing request correlation"
    completion = by_correlation[event["correlation_id"]]
    for field in ("correlation_id", "trace_id", "span_id"):
        assert event.get(field) == completion.get(field), f"ERROR projection mismatch: {field}"
    path = completion["url"]["path"]
    assert path in ("/mvc/error", "/mvc/async-error", "/managed/error", "/excluded/missing-context")
    # ManagedController is PLATFORM; ExcludedController bypasses the identity snapshot producer.
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
assert len(governed_errors) == 4, "two MVC, one managed and one excluded Advice failure expected"
assert len(native_errors) == 5, "five native servlet/container diagnostics remain outside Advice ownership"
for path in ("/fixture/application-io", "/fixture/wrapped-application-io"):
    matches = [event for event in canonical if event["url"]["path"] == path]
    assert len(matches) == 1
    assert matches[0]["http"]["response"]["status_code"] == 500
duplicates = {correlation: sum(row["correlation_id"] == correlation for row in error_rows)
              for correlation in by_correlation
              if sum(row["correlation_id"] == correlation for row in error_rows) > 1}
assert not duplicates, "Advice must emit one final ERROR per failed request"
native_rows = [{"line": event_lines[id(event)], "logger": event["log"]["logger"],
                "message": event.get("message"), "correlation_id": event.get("correlation_id"),
                "scope": "native-container-outside-advice-acceptance"} for event in native_errors]
print(json.dumps({"canonical": len(canonical), "exportedServerSpans": len(by_span),
                  "unsampled": len(unsampled), "sensitiveMatches": [], "errors": error_rows,
                  "requestBoundDuplicateErrors": duplicates,
                  "nativeContainerErrors": native_rows,
                  "errorOwnershipScope": "Spring MVC ApiExceptionHandler unexpected exceptions only",
                  "errorOwnershipComplete": not duplicates}, ensure_ascii=False, indent=2))
