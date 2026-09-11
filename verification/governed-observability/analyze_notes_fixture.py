#!/usr/bin/env python3
"""Join standalone Notes HTTP/DB/Kafka receipts with actual stdout and Agent exports."""
import collections
import hashlib
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
assert (root / "notes-agent.exit").read_text().strip() == "0"
receipt = json.loads((root / "notes-receipt.json").read_text())
status = json.loads((root / "receiver-status.json").read_text())
assert status["spans"] > 0 and status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["metrics"] == status["postRequestsBySignal"]["logs"] == 0
raw = (root / "notes-agent.log").read_text()
assert "notes-private-body-sentinel" not in raw
logs = []
for line in raw.splitlines():
    try:
        event = json.loads(line)
        if isinstance(event, dict) and isinstance(event.get("log"), dict):
            logs.append(event)
    except ValueError:
        pass
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
by_span = {span["spanId"]: span for span in spans}
assert len(by_span) == len(spans)
assert all(span["resource"]["telemetry.distro.version"] == "2.31.1" and
           span["resource"]["telemetry.sdk.version"] == "1.65.0" and
           span["resource"]["service.name"] == "simple-notes-demo" for span in spans)
assert all(not span.get("events") for span in spans), "no auto exception/body capture"


def only(rows):
    assert len(rows) == 1, f"expected one match, got {len(rows)}"
    return rows[0]


def parent(value):
    version, trace, span, flags = value.split("-")
    assert version == "00" and flags in ("01", "03")
    return {"traceId": trace, "spanId": span}


http = [event for event in logs if event["log"]["logger"] == "http.request"]
assert len(http) == len(receipt["requests"]) == 10
assert len({event["correlation_id"] for event in http}) == 10
for row in receipt["requests"]:
    event = only([event for event in http if event["correlation_id"] == row["correlation"]])
    assert event["trace_id"] == row["trace"] and event["http"]["response"]["status_code"] == row["status"]
    assert event["log"]["level"] == "INFO" and event["duration_ms"] >= 0
    assert event["service"]["name"] == "simple-notes-demo"
    assert event["correlation_id"] != "untrusted-notes-correlation"
    if row["label"] == "anonymous":
        assert not any(key in event for key in ("tenant_id", "actor_id", "initiator_id", "user_id"))
    else:
        subject = "reader" if row["label"] in ("read", "forbidden", "unsampled") else "bob" if row["label"] == "isolated" else "alice"
        assert event["tenant_id"] == ("tenant-b" if row["label"] == "isolated" else "tenant-a")
        assert event["actor_type"] == event["initiator_type"] == "USER"
        assert event["actor_id"] == event["initiator_id"] == event["user_id"] == subject
    if row["label"] == "unsampled":
        assert event["trace_flags"] == "00"
        assert not any(span["traceId"] == row["trace"] for span in spans)
    else:
        server = by_span[event["span_id"]]
        assert server["kind"] == "SERVER" and server["traceId"] == row["trace"]

creation = parent(receipt["creation"])
append = by_span[creation["spanId"]]
assert append["name"] == "outbox.append" and append["traceId"] == creation["traceId"]
create = only([event for event in http if event["correlation_id"] == receipt["requests"][0]["correlation"]])
assert append["parentSpanId"] == create["span_id"] and append["traceId"] == create["trace_id"]
redrive = only([span for span in spans if span["name"] == "outbox.redrive"])
assert not redrive.get("parentSpanId") and redrive["links"] == [creation]
assert redrive["traceId"] != creation["traceId"]
attempts = sorted([span for span in spans if span["name"] == "outbox.publish" and
                   span["attributes"]["messaging.message.id"] == receipt["durable_message"]],
                  key=lambda span: span["startUnixNano"])
assert len(attempts) == 4
assert [(span["attributes"]["outbox.transport.result"], span["attributes"]["outbox.write.result"])
        for span in attempts] == [("success", "failed"), ("failure", "retry"), ("failure", "terminal"), ("success", "published")]
for attempt in attempts:
    assert attempt["links"] == [creation]
for attempt in attempts[:3]:
    assert (attempt["traceId"], attempt["parentSpanId"]) == (creation["traceId"], creation["spanId"])
assert (attempts[-1]["traceId"], attempts[-1]["parentSpanId"]) == (redrive["traceId"], redrive["spanId"])

consumed = [event for event in logs if event["log"]["logger"] == "mq.consume"]
assert len(consumed) == len(receipt["wire"]) == receipt["consumer_offset"] == 4
assert collections.Counter(row["id"] for row in receipt["wire"])[receipt["durable_message"]] == 2
assert len([span for span in spans if span["kind"] == "PRODUCER"]) == 4
for wire in receipt["wire"]:
    transport = parent(wire["transport"])
    original = parent(wire["creation"])
    assert original != transport
    producer = by_span[transport["spanId"]]
    assert producer["traceId"] == transport["traceId"] and producer["kind"] == "PRODUCER"
    assert producer["attributes"]["messaging.kafka.message.offset"] == wire["offset"]
    assert producer["status"].get("code") != 2
    event = only([event for event in consumed if event["message_id"] == wire["id"] and event["trace_id"] == transport["traceId"]])
    assert event["log"]["level"] == "INFO" and event["event"]["outcome"] == "success"
    assert event["correlation_id"] == wire["correlationid"]
    assert event["actor_type"] == wire["actortype"] == "SERVICE"
    assert event["actor_id"] == wire["actorsubject"] == "notes-demo"
    assert event["tenant_id"] == wire["tenantid"] == "tenant-a"
    assert event["initiator_id"] == wire["initiatorsubject"] == "alice"
    process = by_span[event["span_id"]]
    assert process["name"] == "mq.process" and process["links"] == [original]
    native = by_span[process["parentSpanId"]]
    assert native["kind"] == "CONSUMER" and native["parentSpanId"] == producer["spanId"]
    assert process["traceId"] == native["traceId"] == producer["traceId"]
    if wire["id"] == receipt["durable_message"]:
        assert original == creation
    if wire["id"] == receipt["immediate_message"]:
        caller = by_span[original["spanId"]]
        assert caller["name"] == "notes.immediate.entry" and producer["parentSpanId"] == caller["spanId"]

assert len(receipt["metrics"]["families"]) == 9
assert receipt["metrics"] == {
    "families": sorted("moduvera.messaging.outbox." + name for name in (
        "broker.ack", "claim.conflicts", "claimed", "cleanup.deleted", "pending", "pending.oldest.seconds", "publish", "stale.token", "terminal")),
    "claimed": 6, "published": 2, "retry": 1, "terminal_results": 2, "stale": 1,
    "conflicts": 1, "cleanup": 1, "pending": 2, "terminal_backlog": 1,
}
coexistence = json.loads((root / "notes-agent-coexistence.json").read_text())
assert coexistence["agent_count"] == 3
assert all(span["resource"]["process.pid"] == coexistence["process_id"] for span in spans)
assert (root / "notes-jacoco.exec").stat().st_size > 0
coverage = ET.parse(root / "notes-jacoco.xml").getroot()
for class_name in ("NoteApplicationService", "NoteController"):
    covered = only([node for node in coverage.findall(".//class") if node.attrib["name"].endswith("/" + class_name)])
    lines = only([node for node in covered.findall("counter") if node.attrib["type"] == "LINE"])
    assert int(lines.attrib["covered"]) > 0, class_name
assert (root / "notes-coverage-report.exit").read_text().strip() == "0"
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
effective = ET.parse(root / "notes-effective-pom.xml").getroot()
assert effective.find("m:parent/m:artifactId", namespace).text == "spring-boot-starter-parent"
assert effective.find("m:parent/m:version", namespace).text == "4.1.1"
classpath = [Path(part) for part in (root / "notes-runtime-classpath.txt").read_text().strip().split(os.pathsep)]
assert any(jar.name.startswith("moduvera-logging-spring-boot-starter-") for jar in classpath)
assert not any("opentelemetry-sdk" in jar.name or "micrometer-tracing" in jar.name for jar in classpath)
minimal = json.loads((root / "minimal-consumers.json").read_text())
assert set(minimal) == {"kernel", "message", "logging"}
for capability, record in minimal.items():
    for path, digest in record["jars"].items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest
    assert (root / ("minimal-" + capability) / "build.exit").read_text().strip() == "0"
    assert (root / ("minimal-" + capability) / "runtime.exit").read_text().strip() == "0"
binding = json.loads((root / "notes-inputs.json").read_text())
checkout = Path(binding["checkout"])
for path, digest in binding["source_sha256"].items():
    assert hashlib.sha256((checkout / path).read_bytes()).hexdigest() == digest, path
for path, digest in binding["runtime_sha256"].items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest, path
print(json.dumps({"result": "PASS", "http": 10, "sampled_http": 9, "unsampled_http": 1,
                  "exported_spans": len(spans), "broker_acks": 4, "actual_application_consumptions": 4,
                  "durable_replay": "one committed Inbox precheck skip after offset advance",
                  "metric_families": 9, "jacoco_coexistence": True, "sdk_metrics_requests": 0, "sdk_logs_requests": 0,
                  "minimal_consumers": 3, "scope": "independent Notes PostgreSQL/Kafka assembly"}, indent=2))
