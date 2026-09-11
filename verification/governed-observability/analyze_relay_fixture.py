#!/usr/bin/env python3
"""Reconcile actual Relay database rows, Kafka records, process checkpoints, SDK spans and safe logs."""
import collections
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
assert (root / "relay-agent.exit").read_text().strip() == "0"
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
index = {(span["traceId"], span["spanId"]): span for span in spans}
assert len(index) == len(spans), "duplicate exported span identities"
publications = [span for span in spans if span["name"] == "outbox.publish"]
producers = [span for span in spans if span["kind"] == "PRODUCER"]
native = [span for span in spans if span["scope"]["name"] == "MySQL Connector/J"]
assert native and publications and producers
for span in spans:
    assert span["resource"]["telemetry.sdk.version"] == "1.65.0"
    assert span["endUnixNano"] >= span["startUnixNano"]
    assert not span.get("events") and not span["status"].get("description")
    assert not {"db.statement", "db.query.text", "db.query", "exception.message", "exception.stacktrace"} & span["attributes"].keys()
    assert not {"process.command_args", "process.command_line"} & span["resource"].keys()
for span in native:
    assert span["scope"]["version"] == "9.7.0" and span["kind"] == "CLIENT"

receipts = []
logs = []
for name in ("relay-postgresql", "relay-mysql", "relay-process"):
    receipts.extend(json.loads((root / (name + "-receipts.json")).read_text()))
    logs.extend(json.loads(line) for line in (root / (name + "-logs.jsonl")).read_text().splitlines() if line)
children = root / "relay-process-children"
for name in ("crash", "restart"):
    logs.extend(json.loads(line) for line in (children / (name + "-logs.jsonl")).read_text().splitlines() if line)
# Ticket 13's additional generation-changing chain is reconciled independently by analyze_redrive_fixture.py.
redrive_id = "postgresql-redrive-chain"
receipts = [receipt for receipt in receipts if receipt.get("id") != redrive_id]
logs = [log for log in logs if log.get("message_id") != redrive_id]
publications = [span for span in publications if span["attributes"]["messaging.message.id"] != redrive_id]
redrive_traces = {span["traceId"] for span in spans if span["attributes"].get("messaging.message.id") == redrive_id}
producers = [span for span in producers if span["traceId"] not in redrive_traces]
rows = collections.defaultdict(list)
for receipt in receipts:
    if "row" in receipt:
        rows[receipt["id"]].append(receipt["row"])
assert len(rows) == 54
pair_keys = ("creation_traceparent", "creation_tracestate", "publication_traceparent", "publication_tracestate", "publication_generation")
for message_id, versions in rows.items():
    assert all(row["message_id"] == message_id and row["correlation_id"] == "original-" + message_id for row in versions)
    assert all(row["publication_generation"] == 7 for row in versions)
    assert all(tuple(row[key] for key in pair_keys) == tuple(versions[0][key] for key in pair_keys) for row in versions)


def carrier(value):
    parts = value.split("-")
    return parts[1], parts[2]


for span in publications:
    assert span["kind"] == "INTERNAL" and span["scope"]["name"] == "io.github.ande1922.moduvera.messaging"
    row = rows[span["attributes"]["messaging.message.id"]][0]
    assert (span["traceId"], span.get("parentSpanId")) == carrier(row["publication_traceparent"])
    creation_trace, creation_span = carrier(row["creation_traceparent"])
    assert span["links"] == [{"traceId": creation_trace, "spanId": creation_span}]
    parent = index[carrier(row["publication_traceparent"])]
    assert parent["name"] == "fixture.relay.publication"
    assert span["startUnixNano"] - parent["endUnixNano"] > 2 * 86400 * 10**9, "ended historical parent must be reused"
    published = span["attributes"]["outbox.write.result"] == "published"
    assert (span["status"].get("code") == 2) == (not published)
    assert span["attributes"]["outbox.transport.result"] in ("success", "failure")
assert len(publications) == 88

canonical = [log for log in logs if log["log"]["logger"] == "task.execute"]
interactions = [log for log in logs if log["log"]["logger"] == "mq.produce"]
recoveries = [log for log in logs if log["log"]["logger"] == "outbox.recovery"]
finals = [log for log in logs if log["log"]["logger"] == "outbox.publish.failure"]
assert len(canonical) == 90 and len(recoveries) == 10 and len(finals) == 5
assert len({(log["trace_id"], log["span_id"]) for log in canonical}) == len(canonical)
for log in logs:
    assert log["correlation_id"].startswith("original-")
    assert log["tenant_id"] == "origin-tenant"
    assert (log["actor_type"], log["actor_id"], log["initiator_type"], log["initiator_id"]) == ("SERVICE", "origin-service", "USER", "origin-user")
    assert "retry" not in log and "retry.attempt" not in log and "retry.max_attempts" not in log
    assert "event.action" not in log and "action" not in log.get("event", {})
    assert len(log["trace_id"]) == 32 and len(log["span_id"]) == 16
    if log["log"]["level"] != "ERROR":
        assert "stack_trace" not in log.get("error", {})
    if "duration_ms" in log:
        assert isinstance(log["duration_ms"], (int, float)) and log["duration_ms"] >= 0
for log in canonical:
    assert log["log"]["level"] == "INFO" and log["task_name"] == "outbox.publish"
    assert log["event"]["outcome"] == ("success" if log["outbox_write_result"] == "published" else "failure")
    assert isinstance(log["outbox_failed_attempts"], int) and isinstance(log["outbox_failure_limit"], int)
    assert log["outbox_failed_attempts"] >= 0 and log["outbox_failure_limit"] >= 1
    identity = log["trace_id"], log["span_id"]
    if log["message_id"].endswith("-unsampled"):
        assert identity not in index
        assert log["trace_id"] == carrier(rows[log["message_id"]][0]["publication_traceparent"])[0]
        assert log["span_id"] != carrier(rows[log["message_id"]][0]["publication_traceparent"])[1]
    else:
        span = index[identity]
        assert span["name"] == "outbox.publish"
        assert span["attributes"]["outbox.transport.result"] == log["transport_result"]
        assert span["attributes"]["outbox.write.result"] == log["outbox_write_result"]
        assert log["duration_ms"] <= (span["endUnixNano"] - span["startUnixNano"]) / 10**6
for log in interactions:
    assert log["log"]["level"] == "INFO" and log["event"]["outcome"] in ("success", "failure")
    assert log["messaging"]["system"] == "kafka" and log["topic"] == "relay-events"
    assert log["messaging"]["message"]["body"]["size"] > 0
    assert not log["message_id"].endswith("-internal")
for log in recoveries:
    assert log["log"]["level"] == "WARN" and log["outbox_failed_attempts"] == 1
for log in finals:
    assert log["log"]["level"] == "ERROR" and log["error"]["code"] == "DEP_OUTBOX_PUBLICATION_FAILED"
    assert log["error"]["stack_trace"] and "duration_ms" not in log and "event" not in log

by_message = collections.defaultdict(list)
for log in canonical:
    by_message[log["message_id"]].append(log)
for database in ("postgresql", "mysql"):
    for classification in ("", "internal-"):
        prefix = f"{database}-{classification}"
        for mode in ("success", "retry", "terminal"):
            for operation, result in (("stale", "stale"), ("write-failure", "failed")):
                message_id = f"{prefix}{operation}-{mode}"
                outcomes = by_message[message_id]
                assert [event["outbox_write_result"] for event in outcomes] == [result, "published"]
                assert all(event["outbox_failed_attempts"] == 0 for event in outcomes)
                assert not any(log["message_id"] == message_id for log in recoveries + finals)
        for operation in ("interrupted", "error"):
            event, = by_message[f"{prefix}{operation}"]
            assert event["outbox_write_result"] == "not_attempted" and event["outbox_failed_attempts"] == 0
        assert by_message[f"{prefix}interrupted"][0]["termination_reason"] == "interrupted"
        assert [event["outbox_failed_attempts"] for event in by_message[f"{prefix}retry-success"]] == [1, 1]
        assert [event["outbox_write_result"] for event in by_message[f"{prefix}terminal"]] == ["retry", "terminal"]
    internal_ids = [message_id for message_id in by_message if message_id.startswith(f"{database}-internal-")]
    assert len(internal_ids) == 11
    for message_id in internal_ids:
        outcomes = by_message[message_id]
        results = [log for log in interactions if log["message_id"] == message_id]
        first = outcomes[0]
        fallback = first["transport_result"] == "failure" and first["outbox_write_result"] in ("stale", "failed", "not_attempted")
        assert len(results) == int(fallback)
        if fallback:
            result, = results
            assert result["event"]["outcome"] == "failure"
            assert (result["trace_id"], result["span_id"]) == (first["trace_id"], first["span_id"])
            assert result["duration_ms"] <= first["duration_ms"]
            assert result["error"]["type"] == ("java.lang.AssertionError" if message_id.endswith("-error") else "java.lang.IllegalStateException")
            if "write-failure" in message_id:
                assert first["error"]["type"] != result["error"]["type"]
            assert not any(log["message_id"] == message_id for log in recoveries + finals)
assert sum("-internal-" in log["message_id"] for log in interactions) == 12

wires = [receipt for receipt in receipts if receipt["phase"] == "broker-wire"]
inboxes = [receipt for receipt in receipts if receipt["phase"] == "inbox"]
assert len(wires) == len(inboxes) == 8
assert collections.Counter(receipt["outcome"] for receipt in inboxes) == {"APPLIED": 5, "DUPLICATE": 3}
assert len(producers) == 10, "eight ACKs and two broker-stop failures, Agent-owned only"
for producer in producers:
    assert producer["scope"]["name"] == "io.opentelemetry.kafka-clients-0.11"
checkpoint, = [receipt for receipt in receipts if receipt["phase"] == "before-kill"]
restart, = [receipt for receipt in receipts if receipt["phase"] == "restart"]
lost = checkpoint["checkpoint"]["trace"]
assert (lost["trace"], lost["span"]) not in index, "killed process cannot export its still-open publish span"
assert restart["firstPid"] != restart["secondPid"] and restart["firstExit"] != 0 and restart["secondExit"] == 0
for mode in ("crash", "restart"):
    assert "Governed OpenTelemetry Agent extension handshake: PASS" in (children / (mode + "-output.log")).read_text()
restarted_span, = [span for span in publications if span["attributes"]["messaging.message.id"] == restart["id"]]
assert restarted_span["resource"]["process.pid"] == restart["secondPid"]
crashed_producer, = [span for span in producers if span.get("parentSpanId") == lost["span"] and span["traceId"] == lost["trace"]]
assert crashed_producer["resource"]["process.pid"] == restart["firstPid"]
assert restart["row"]["status"] == "PUBLISHED" and checkpoint["row"]["status"] == "PENDING"
assert {**restart["row"], "status": "PENDING"} == checkpoint["row"]
assert restart["completion"]["relayState"] == "WAITING" and restart["completion"]["canonicalCount"] == 1
for wire in wires:
    assert wire["headerCount"] == 1
    row = rows[wire["id"]][0]
    assert wire["creation"] == row["creation_traceparent"]
    producer = index[carrier(wire["traceparent"])]
    assert producer["kind"] == "PRODUCER" and producer["status"].get("code") != 2
    attempt = producer["traceId"], producer["parentSpanId"]
    assert attempt == (lost["trace"], lost["span"]) or index[attempt]["name"] == "outbox.publish"
    assert producer["traceId"] == carrier(row["publication_traceparent"])[0]
    if attempt in index:
        assert index[attempt]["startUnixNano"] <= producer["startUnixNano"] <= producer["endUnixNano"] <= index[attempt]["endUnixNano"]
assert len({wire["traceparent"] for wire in wires}) == len(wires)
crash_logs = [json.loads(line) for line in (children / "crash-logs.jsonl").read_text().splitlines()]
assert len(crash_logs) == 1 and crash_logs[0]["log"]["logger"] == "mq.produce"
assert (crash_logs[0]["trace_id"], crash_logs[0]["span_id"]) == (lost["trace"], lost["span"])
assert sum(log["message_id"] == restart["id"] for log in canonical) == 1

status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    for path in list(root.glob("*.json*")) + list(root.glob("*.log")) + list(children.glob("*.json*")) + list(children.glob("*.log")):
        if path.name != "sentinels.json":
            assert sentinel not in path.read_text(), path.name
print(json.dumps({"databases": 2, "messages": len(rows), "canonical": len(canonical),
                  "publishSpans": len(publications), "internalFailureResults": 12, "unsampledAttempts": 2, "lostOpenCrashSpan": 1,
                  "kafkaProducerSpans": len(producers), "acknowledgedRecords": len(wires),
                  "inboxApplied": 5, "inboxDuplicates": 3, "recoveryWarnings": len(recoveries),
                  "finalErrors": len(finals), "nativeMysqlSpans": len(native),
                  "exportedSpans": len(spans), "sensitiveMatches": []}, indent=2))
