#!/usr/bin/env python3
"""Reconcile current ImmediatePublication ACK, identity and locked-Agent evidence."""
import collections
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
raw = (root / "immediate-agent.log").read_text()
assert (root / "immediate-agent.exit").read_text().strip() == "0"
events = []
for line in raw.splitlines():
    try:
        event = json.loads(line)
        if isinstance(event, dict) and "log" in event:
            events.append(event)
    except ValueError:
        pass
receipt = json.loads((root / "immediate-receipts.json").read_text())
assert receipt["acknowledged"] == 5 and receipt["sends"] == 6
callers = {row["id"]: row for row in receipt["receipts"] if row["phase"] == "caller"}
broker = {row["id"]: row for row in receipt["receipts"] if row["phase"] == "broker"}
assert len(callers) == 8 and set(broker) == {"boundary", "internal", "existing", "unsampled", "absent"}
failure_receipt = next(row for row in receipt["receipts"] if row["phase"] == "failure")
assert failure_receipt["broker_outcome"] == "unknown" and failure_receipt["sends"] == 6

spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
by_span = {span["spanId"]: span for span in spans}
assert len(by_span) == len(spans) == 12
assert collections.Counter((span["name"], span["kind"]) for span in spans) == {
    ("fixture.immediate", "INTERNAL"): 6,
    ("immediate-boundary publish", "PRODUCER"): 4,
    ("immediate-internal publish", "PRODUCER"): 1,
    ("application.boundary-out-0 process", "CONSUMER"): 1,
}
for span in spans:
    assert span["resource"]["telemetry.sdk.version"] == "1.65.0"
    assert span["links"] == []
    assert not span.get("events"), "automatic exception/body capture must remain disabled/sanitized"
producers = {span["attributes"]["messaging.kafka.message.key"].removeprefix("key-"): span
             for span in spans if span["kind"] == "PRODUCER"}
assert set(producers) == {"boundary", "internal", "existing", "absent", "broker-failure"}
for identity, row in broker.items():
    caller = callers[identity]
    assert row["correlation"] == caller["correlation"] == "corr-" + identity
    assert row["actor"] == "immediate-publisher" and row["initiator"] == "user-" + identity
    assert row["tenant"] == ("tenant-b" if identity == "internal" else "tenant-a")
    assert row["body_size"] == 43
    parent = re.search(r"(?:^|,)traceparent:([^,]+)", row["headers"]).group(1)
    _, trace, span_id, flags = parent.split("-")
    if identity == "unsampled":
        assert trace == caller["trace"] and flags == "00" and span_id != caller["span"]
        assert row["creation"] == "00-" + caller["trace"] + "-" + caller["span"] + "-00"
        assert row["creation_state"] == "vendor=unsampled" and "tracestate:vendor=unsampled" in row["headers"]
        assert not any(span["traceId"] == trace for span in spans)
        continue
    producer = producers[identity]
    assert (trace, span_id) == (producer["traceId"], producer["spanId"])
    assert flags == "03" and producer["status"].get("code") != 2
    assert producer["attributes"]["messaging.kafka.message.offset"] >= 0
    if identity == "absent":
        assert row["creation"] == "absent" and not caller["valid"]
        native_root = by_span[producer["parentSpanId"]]
        assert native_root["name"] == "application.boundary-out-0 process"
        assert not native_root.get("parentSpanId") and native_root["traceId"] == producer["traceId"]
    else:
        assert (producer["traceId"], producer["parentSpanId"]) == (caller["trace"], caller["span"])
        if identity == "existing":
            assert row["creation"] == "00-" + "3" * 32 + "-" + "4" * 16 + "-01"
            assert row["creation_state"] == "vendor=original"
        else:
            assert row["creation"] == "00-" + caller["trace"] + "-" + caller["span"] + "-03"
        assert row["creation"] != parent

for identity in ("writable-transaction", "readonly-transaction"):
    caller = callers[identity]
    assert caller["span"] in by_span
    assert sum(span["traceId"] == caller["trace"] for span in spans) == 1, "rejected transaction cannot send"
failed_producer = producers["broker-failure"]
assert failed_producer["status"].get("code") == 2
assert failed_producer["attributes"]["messaging.kafka.message.offset"] == -1
assert failed_producer["parentSpanId"] == callers["broker-failure"]["span"]

results = [event for event in events if event["log"]["logger"] == "mq.produce"]
assert len(results) == 5
assert {event["message_id"] for event in results} == {"boundary", "existing", "unsampled", "absent", "broker-failure"}
assert collections.Counter(event["event"]["outcome"] for event in results) == {"success": 4, "failure": 1}
for event in results:
    identity = event["message_id"]
    caller = callers[identity]
    assert event["log"]["level"] == "INFO" and event["duration_ms"] >= 0
    assert event["messaging"] == {"system": "kafka", "message": {"body": {"size": 43}}}
    assert event["topic"] == "immediate-boundary"
    assert event["correlation_id"] == caller["correlation"] and event["tenant_id"] == "tenant-a"
    assert event["actor_id"] == "immediate-publisher" and event["initiator_id"] == "user-" + identity
    assert "retry" not in event and "stack_trace" not in event.get("error", {})
    if caller["valid"]:
        assert (event["trace_id"], event["span_id"]) == (caller["trace"], caller["span"])
    else:
        assert "trace_id" not in event and "span_id" not in event
    if identity == "broker-failure":
        assert event["error"]["type"] == failure_receipt["type"]
errors = [event for event in events if event["log"]["level"] == "ERROR"]
assert len(errors) == 1 and errors[0]["log"]["logger"] == "immediate.fixture.final"
assert errors[0]["error"]["code"] == "DEP_FIXTURE_BROKER"
propagating = [event for event in events if event["log"]["logger"] == "mq.produce.propagating"]
assert not propagating, "default INFO runtime must omit process-only propagation diagnostics"
for event in errors:
    assert event["correlation_id"] == "corr-broker-failure"
    assert event["trace_id"] == callers["broker-failure"]["trace"]
    assert event["span_id"] == callers["broker-failure"]["span"]
    assert event["actor_id"] == "immediate-publisher" and event["initiator_id"] == "user-broker-failure"
    assert "event" not in event and "retry" not in event and "duration_ms" not in event
assert not any(event["log"]["level"] in ("WARN", "ERROR") and event["log"]["logger"].startswith("mq.produce") for event in events)
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    assert sentinel not in raw and sentinel not in (root / "spans.jsonl").read_text()
print(json.dumps({"applicationCalls": 8, "producerApiSends": 6, "realAcknowledgedMessages": 5,
                  "rejectedDatabaseTransactions": 2, "resultInfo": 5, "boundarySuccessInfo": 4,
                  "internalSuccessInfo": 0, "failureResultInfo": 1, "propagationInfo": 0, "callerFinalError": 1,
                  "sampledProducerSpans": 5, "unsampledProducerAttempt": 1, "exportedSpans": len(spans),
                  "agentIntegrationRootWithoutCallerContext": 1, "creationAndTransportReconciled": True,
                  "failedBrokerOutcome": "unknown", "sensitiveMatches": []}, indent=2))
