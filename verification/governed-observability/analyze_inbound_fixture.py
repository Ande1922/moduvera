#!/usr/bin/env python3
"""Reconcile raw Broker headers, real Inbox receipts, owned logs and locked-Agent spans."""
import collections
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
raw = (root / "inbound-agent.log").read_text()
assert (root / "inbound-agent.exit").read_text().strip() == "0"
assert raw.count("Governed OpenTelemetry Agent extension handshake: PASS") == 1
events = []
for line in raw.splitlines():
    try:
        event = json.loads(line)
        if isinstance(event, dict) and isinstance(event.get("log"), dict):
            events.append(event)
    except ValueError:
        pass
receipt = json.loads((root / "inbound-receipts.json").read_text())
assert receipt["committed"] - receipt["baseline"] == 13
rows = receipt["receipts"]
wire = [row for row in rows if row["phase"] == "broker-wire"]
transports = [row for row in rows if row["phase"] == "transport"]
attempts = [row for row in rows if row["phase"] == "attempt"]
restored = [row for row in rows if row["phase"] == "restored"]
assert (len(wire), len(transports), len(attempts), len(restored)) == (13, 14, 18, 14)
assert sorted(row["offset"] for row in wire) == list(range(13))
assert max(row["batch_size"] for row in wire) > 1, "must exercise an actual multi-record poll"
for before, after in zip(transports, restored):
    assert (before["id"], before["trace"], before["span"]) == (after["id"], after["trace"], after["span"])

spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
by_span = {span["spanId"]: span for span in spans}
assert len(by_span) == len(spans) == 57
assert collections.Counter((span["name"], span["kind"]) for span in spans) == {
    ("fixture.publish", "INTERNAL"): 12,
    ("inbound-causality publish", "PRODUCER"): 12,
    ("inbound-causality process", "CONSUMER"): 12,
    ("mq.process", "INTERNAL"): 18,
    ("inbound-causality-dlq publish", "PRODUCER"): 2,
    ("invalid!topic publish", "PRODUCER"): 1,
}
for span in spans:
    assert span["resource"]["telemetry.sdk.version"] == "1.65.0"
    assert span["resource"]["telemetry.distro.version"] == "2.31.1"
    assert span["endUnixNano"] >= span["startUnixNano"]

canonical = [event for event in events if event["log"]["logger"] == "mq.consume"
             and event["log"]["level"] == "INFO"]
warnings = [event for event in events if event["log"]["logger"] == "mq.consume"
            and event["log"]["level"] == "WARN"]
errors = [event for event in events if event["log"]["level"] == "ERROR"]
all_canonical = canonical
fatal_canonical = [event for event in canonical if event["message_id"] == "fatal-error"]
canonical = [event for event in canonical if event["message_id"] != "fatal-error"]
business_errors = [event for event in errors if event["log"]["logger"] == "mq.consume.failure"]
recovery_errors = [event for event in errors if event["log"]["logger"] == "mq.consume.recovery.failure"]
container_errors = [event for event in errors if event["log"]["logger"] == "org.springframework.kafka.listener.KafkaMessageListenerContainer"]
background_errors = [event for event in errors if event["log"]["logger"] == "org.apache.kafka.clients.Metadata"]
assert (len(canonical), len(fatal_canonical), len(warnings)) == (18, 1, 4)
assert (len(errors), len(business_errors), len(recovery_errors), len(container_errors), len(background_errors)) == (6, 3, 1, 1, 1)
assert "invalid topics [invalid!topic]" in background_errors[0]["message"]
assert background_errors[0]["error"] == {"code": "SYS_UNEXPECTED"} and "correlation_id" not in background_errors[0]
assert not any(event["log"]["level"] == "ERROR" and event["log"]["logger"] in (
    "org.springframework.kafka.support.LoggingProducerListener",
    "org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder") for event in events)
assert collections.Counter(event["event"]["outcome"] for event in canonical) == {"success": 11, "failure": 7}
by_attempt = {row["span"]: row for row in attempts}
assert len(by_attempt) == len(attempts)
canonical_by_span = {event["span_id"]: event for event in canonical}
assert set(canonical_by_span) == set(by_attempt)
parents = {row["span"]: row for row in transports}
for event in canonical:
    observation = by_attempt[event["span_id"]]
    identity = observation["id"]
    assert event["message_id"] == identity
    for key, receipt_key in [("correlation_id", "correlation"), ("tenant_id", "tenant"),
                             ("actor_id", "actor"), ("initiator_id", "initiator")]:
        assert event[key] == observation[receipt_key]
    assert event["correlation_id"] == "corr-" + identity
    assert event["actor_id"] == "local-consumer" and event["initiator_id"] == "user-" + identity
    assert re.fullmatch(r"[0-9a-f]{32}", event["trace_id"]) and event["trace_id"] != "0" * 32
    assert re.fullmatch(r"[0-9a-f]{16}", event["span_id"]) and event["span_id"] != "0" * 16
    assert event["duration_ms"] >= 0 and event["messaging"]["system"] == "kafka"
    assert event["topic"] == "inbound-causality"
    assert "message" not in event.get("error", {}) and "stack_trace" not in event.get("error", {})
    if identity in ("retry", "exhausted"):
        assert event["retry"] == {"attempt": observation["attempt"], "max_attempts": 3}
        if observation["attempt"] < 3:
            assert event["disposition"] == "retry"
        elif identity == "exhausted":
            assert event["disposition"] == "dead_letter"
        else:
            assert "disposition" not in event
    elif identity == "terminal":
        assert "retry" not in event and event["disposition"] == "dead_letter"
    else:
        assert "retry" not in event and "disposition" not in event
    if identity == "unsampled":
        assert event["trace_id"] == "3" * 32
        assert not any(span["traceId"] == event["trace_id"] for span in spans)
        parent = next(row for row in transports if row["id"] == identity)
    else:
        span = by_span[event["span_id"]]
        assert span["name"] == "mq.process" and span["kind"] == "INTERNAL"
        assert span["traceId"] == event["trace_id"] == observation["trace"]
        assert (span["status"].get("code") == 2) == (event["event"]["outcome"] == "failure")
        if identity == "no-current-context":
            assert not span.get("parentSpanId")
            parent = next(row for row in transports if row["id"] == identity)
            assert parent["trace"] == "0" * 32 and parent["span"] == "0" * 16
            assert sum(item["traceId"] == span["traceId"] for item in spans) == 1
        else:
            parent = parents[span["parentSpanId"]]
            consumer = by_span[parent["span"]]
            assert consumer["kind"] == "CONSUMER" and parent["trace"] == span["traceId"]
        expected_links = [] if identity in ("invalid-creation", "no-creation") else [
            {"traceId": "1" * 32, "spanId": "2" * 16}]
        assert span["links"] == expected_links
        assert span["traceId"] != "1" * 32, "creation cannot replace the delivery parent"
    assert event["messaging"]["message"]["body"]["size"] == parent["body_size"]

for event in warnings:
    original = canonical_by_span[event["span_id"]]
    assert event["message_id"] in ("retry", "exhausted")
    assert event["retry"]["attempt"] in (1, 2) and event["retry"]["max_attempts"] == 3
    assert event["retry"] == original["retry"] and event["correlation_id"] == original["correlation_id"]
    assert event["disposition"] == "retry" and "event" not in event
for event in business_errors:
    original = canonical_by_span[event["span_id"]]
    assert event["log"]["logger"] == "mq.consume.failure" and event["message"] == "消息处理最终失败"
    assert event["error"]["code"] == "SYS_UNEXPECTED"
    assert original["message_id"] in ("terminal", "exhausted", "dlq-failed")
    for key in ("trace_id", "correlation_id", "tenant_id", "actor_id", "initiator_id"):
        assert event[key] == original[key]
    assert "event" not in event and "duration_ms" not in event and "retry" not in event

for row in wire:
    identity = row["id"]
    if identity in ("missing-parent", "invalid-parent"):
        assert row["traceparent"] == ("absent" if identity == "missing-parent" else "invalid-transport")
        current = next(item for item in transports if item["id"] == identity)
        consumer = by_span[current["span"]]
        assert not consumer.get("parentSpanId") and consumer["links"] == []
    else:
        version, trace, parent_span, flags = row["traceparent"].split("-")
        assert version == "00"
        if identity == "unsampled":
            assert trace == "3" * 32 and int(flags, 16) & 1 == 0
            assert not any(span["traceId"] == trace for span in spans)
        else:
            producer = by_span[parent_span]
            assert producer["kind"] == "PRODUCER" and producer["traceId"] == trace
            consumer = next(span for span in spans if span["kind"] == "CONSUMER" and span["traceId"] == trace)
            assert consumer["parentSpanId"] == parent_span
            current = parents[consumer["spanId"]]
            assert current["trace"] == trace and current["body_size"] == row["body_size"]
            assert parent_span != consumer["spanId"], "raw Broker and adapted Spring headers must be distinguished"
    if identity == "invalid-creation":
        assert row["creation"] == "invalid-trace"
    elif identity == "no-creation":
        assert row["creation"] == "absent"
    else:
        assert row["creation"] == "00-" + "1" * 32 + "-" + "2" * 16 + "-01"

dlq = [span for span in spans if span["name"] == "inbound-causality-dlq publish"]
assert sorted(span["attributes"]["messaging.kafka.message.offset"] for span in dlq) == [0, 1]
for span in dlq:
    assert span["status"].get("code") != 2
    final = next(event for event in business_errors if event["trace_id"] == span["traceId"])
    assert span["parentSpanId"] == by_span[final["span_id"]]["parentSpanId"]

failed_send = next(span for span in spans if span["name"] == "invalid!topic publish")
assert failed_send["status"].get("code") == 2
assert failed_send["attributes"].get("messaging.kafka.message.offset", -1) == -1
failed_attempt = next(event for event in canonical if event["message_id"] == "dlq-failed")
assert "disposition" not in failed_attempt
assert failed_send["traceId"] == failed_attempt["trace_id"]
recovery = recovery_errors[0]
assert recovery["message"] == "消息死信投递最终失败" and recovery["error"]["code"] == "DEP_DLQ_SEND"
for key in ("trace_id", "span_id", "correlation_id", "tenant_id", "actor_id", "initiator_id"):
    assert recovery[key] == failed_attempt[key]
assert "event" not in recovery and "duration_ms" not in recovery and "retry" not in recovery

fatal = json.loads((root / "inbound-error-receipts.json").read_text())
assert fatal["baseline"] == fatal["committed"] == receipt["committed"] == 13
assert fatal["broker_end"] == 15
assert fatal["stop"] == {"reason": "ERROR", "business_scope_present": False, "delivery_scope_present": False}
fatal_rows = fatal["receipts"]
assert [row["id"] for row in fatal_rows if row["phase"] == "attempt"] == ["fatal-error"]
fatal_transport = next(row for row in fatal_rows if row["phase"] == "transport")
fatal_attempt = next(row for row in fatal_rows if row["phase"] == "attempt")
fatal_restored = next(row for row in fatal_rows if row["phase"] == "restored")
fatal_wire = next(row for row in fatal_rows if row["phase"] == "broker-wire" and row["id"] == "fatal-error")
assert fatal_wire["offset"] == 13
assert (fatal_transport["trace"], fatal_transport["span"]) == (fatal_restored["trace"], fatal_restored["span"])
fatal_span = by_span[fatal_attempt["span"]]
assert fatal_span["parentSpanId"] == fatal_transport["span"]
assert fatal_span["traceId"] == fatal_transport["trace"] == fatal_attempt["trace"]
assert fatal_span["status"].get("code") == 2 and fatal_span["links"] == [{"traceId": "1" * 32, "spanId": "2" * 16}]
assert fatal_transport["span"] not in by_span  # Explicit locked-Agent baseline limitation; see README.
assert fatal_transport["traceparent"] == "00-" + fatal_transport["trace"] + "-" + fatal_transport["span"] + "-03"
fatal_producer = by_span[fatal_wire["traceparent"].split("-")[2]]
assert fatal_producer["kind"] == "PRODUCER" and fatal_producer["traceId"] == fatal_transport["trace"]
final = container_errors[0]
assert final["message"] == "Stopping container due to an Error" and final["error"]["type"] == "java.lang.AssertionError"
for event in (fatal_canonical[0], final):
    for key, receipt_key in [("correlation_id", "correlation"), ("tenant_id", "tenant"),
                             ("actor_id", "actor"), ("initiator_id", "initiator"),
                             ("trace_id", "trace"), ("span_id", "span")]:
        assert event[key] == fatal_attempt[receipt_key]
assert fatal_canonical[0]["event"]["outcome"] == "failure" and fatal_canonical[0]["duration_ms"] >= 0
assert "retry" not in fatal_canonical[0] and "disposition" not in fatal_canonical[0]
assert "event" not in final and "duration_ms" not in final and "retry" not in final
assert sum(span["traceId"] == fatal_transport["trace"] for span in spans) == 3

status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    assert sentinel not in raw and sentinel not in (root / "spans.jsonl").read_text()
print(json.dumps({"brokerDeliveries": len(wire) + 1, "brokerAcknowledgedMessages": fatal["broker_end"], "directFallbackExecutions": 1,
                  "actualAttempts": len(all_canonical), "retryDecisions": len(warnings),
                  "finalErrors": len(errors) - len(background_errors), "backgroundMetadataErrors": len(background_errors),
                  "sampledProcessingSpans": 18,
                  "unsampledAttempts": 1, "agentConsumerSpans": 12, "fatalValidAgentContextsWithoutExport": 1, "originalBinderDlqSends": 2,
                  "committedBusinessAndInboxRecords": 10, "exportedSpans": len(spans),
                  "pollBatchSizes": sorted(set(row["batch_size"] for row in wire)),
                  "rawAndAdaptedHeadersReconciled": True, "sensitiveMatches": []}, indent=2))
