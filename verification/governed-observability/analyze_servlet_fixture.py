#!/usr/bin/env python3
"""Verify real Agent/canonical relations; report existing container error gaps separately."""
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
events = []
for line in (root / "servlet-agent.log").read_text().splitlines():
    try:
        events.append(json.loads(line))
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
container_gaps = [event["log"]["logger"] for event in errors if "correlation_id" not in event]
print(json.dumps({"canonical": len(canonical), "exportedServerSpans": len(by_span),
                  "unsampled": len(unsampled), "sensitiveMatches": [],
                  "existingContainerErrorCorrelationGaps": container_gaps}, ensure_ascii=False, indent=2))
