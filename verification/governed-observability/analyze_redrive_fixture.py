#!/usr/bin/env python3
"""Reconcile atomic redrive decisions, actual process loss, current transport causality and Inbox deduplication."""
import collections
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
assert (root / "relay-agent.exit").read_text().strip() == "0"
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
index = {(span["traceId"], span["spanId"]): span for span in spans}
assert len(index) == len(spans)


def carrier(value):
    version, trace, span, flags = value.split("-")
    assert version == "00" and len(trace) == 32 and len(span) == 16 and int(flags, 16) & 1
    return trace, span


def events(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def assert_identity(log, actor):
    assert log["correlation_id"] == "original-" + log["message_id"]
    assert (log["actor_type"], log["actor_id"], log["initiator_type"], log["initiator_id"], log["tenant_id"]) == (
        "SERVICE", actor, "USER", "origin-user", "origin-tenant")
    assert "action" not in log.get("event", {}) and "event.action" not in log
    if log["log"]["level"] != "ERROR":
        assert "stack_trace" not in log.get("error", {})


for database in ("postgresql", "mysql"):
    receipts = json.loads((root / f"redrive-{database}-receipts.json").read_text())
    logs = events(root / f"redrive-{database}-logs.jsonl")
    assert len(receipts) == 10 and len(logs) == 6
    roots = [span for span in spans if span["name"] == "outbox.redrive"
             and span["attributes"]["messaging.message.id"].startswith(database + "-redrive-")
             and span["attributes"]["messaging.message.id"] != "postgresql-redrive-chain"]
    assert len(roots) == 9
    for receipt in receipts:
        row = receipt["row"]
        identity = receipt["id"]
        assert row["message_id"] == identity and row["correlation_id"] == "original-" + identity
        if receipt["phase"] == "rejections":
            assert row["status"] == "TERMINAL" and row["publication_generation"] == 7 and receipt["wakes"] == 0
            assert not any(span["attributes"]["messaging.message.id"] == identity for span in roots)
            continue
        span = index[receipt["rootTrace"], receipt["rootSpan"]]
        assert span in roots and span["kind"] == "INTERNAL" and not span.get("parentSpanId")
        assert not receipt["recordingAfterCompletion"] and receipt["allImmutableColumnsAndPayloadVerified"]
        assert row["attempt_count"] == (6 if receipt["phase"] == "generation-9" else 5)
        failed = receipt["phase"] in ("outer-rollback", "owned-rollback-only", "sql-rollback")
        assert (span["status"].get("code") == 2) == failed
        assert row["status"] == ("TERMINAL" if failed else "PENDING")
        assert row["publication_generation"] == (7 if failed else 9 if receipt["phase"] == "generation-9" else 8)
        if not failed:
            assert carrier(row["publication_traceparent"]) == (span["traceId"], span["spanId"])
        links = {(link["traceId"], link["spanId"]) for link in span["links"]}
        assert len(links) == len(span["links"])
        creation = row["creation_traceparent"]
        valid_creation = creation is not None and creation.startswith("00-")
        if valid_creation:
            links.remove(carrier(creation))
            assert span["traceId"] != carrier(creation)[0]
        assert len(links) == 1
        management = index[links.pop()]
        assert management["name"] == "fixture.relay.management" and not management.get("parentSpanId")
        assert management["attributes"]["fixture.id"].startswith(identity)
        assert span["traceId"] != management["traceId"]
        assert management["startUnixNano"] <= span["startUnixNano"]
        if receipt["phase"] in ("outer-commit", "outer-rollback"):
            assert management["endUnixNano"] < span["endUnixNano"]
        else:
            assert span["endUnixNano"] <= management["endUnixNano"]
        if receipt["phase"] == "sql-rollback":
            assert span["attributes"]["error.type"] == (
                "org.springframework.jdbc.UncategorizedSQLException" if database == "mysql"
                else "org.springframework.dao.DataIntegrityViolationException")
    for log in logs:
        assert_identity(log, "origin-service")
        assert log["log"] == {"level": "WARN", "logger": "outbox.recovery"}
        assert "event" not in log and "error" not in log and "retry" not in log
        span = index[log["trace_id"], log["span_id"]]
        assert span in roots and span["status"].get("code") != 2
    assert len({(log["trace_id"], log["span_id"]) for log in logs}) == 6

identity = "postgresql-redrive-chain"
receipts = json.loads((root / "redrive-inbound-receipts.json").read_text())
assert all(row["id"] == identity for row in receipts)
rows = [receipt for receipt in receipts if "row" in receipt]
assert len(rows) == 9
by_phase = collections.defaultdict(list)
for receipt in receipts:
    by_phase[receipt["phase"]].append(receipt)
original, = by_phase["redrive-original-terminal"]
lost_root, = by_phase["redrive-root-lost"]
terminal, = by_phase["redrive-second-terminal"]
third, = by_phase["redrive-third-accepted"]
final, = by_phase["redrive-final-published"]
assert final["row"]["status"] == "PUBLISHED" and final["row"]["attempt_count"] == 4
assert (final["records"], final["applied"], final["duplicates"], final["immutableColumnsAndPayloadVerified"]) == (4, 1, 3, True)
assert original["row"]["status"] == terminal["row"]["status"] == "TERMINAL"
assert original["row"]["attempt_count"] == 1 and terminal["row"]["attempt_count"] == 3
assert third["wakes"] == 1
creation = carrier(original["row"]["creation_traceparent"])
append = index[creation]
assert append["name"] == "outbox.append" and append["kind"] == "INTERNAL"
parents = {0: carrier(original["row"]["publication_traceparent"]),
           1: carrier(lost_root["row"]["publication_traceparent"]),
           2: carrier(third["row"]["publication_traceparent"])}
assert parents[0] == creation and len({trace for trace, _ in parents.values()}) == 3
assert parents[1] not in index, "killed committed redrive anchor must never be reconstructed as an exported span"
assert lost_root["exit"] != 0 and lost_root["checkpoint"]["pid"] == lost_root["pid"]
third_root = index[parents[2]]
assert third_root["name"] == "outbox.redrive" and not third_root.get("parentSpanId")
assert third_root["status"].get("code") != 2
assert {"traceId": creation[0], "spanId": creation[1]} in third_root["links"]
assert len(third_root["links"]) == 2
for receipt in rows:
    row = receipt["row"]
    assert row["message_id"] == identity and row["correlation_id"] == "original-" + identity
    assert carrier(row["creation_traceparent"]) == creation
    assert carrier(row["publication_traceparent"]) == parents[row["publication_generation"]]
    assert row["creation_tracestate"] == original["row"]["creation_tracestate"]
assert [row["row"]["attempt_count"] for row in by_phase["redrive-automatic-retry"]] == [2, 4]

publications = [span for span in spans if span["name"] == "outbox.publish" and span["attributes"]["messaging.message.id"] == identity]
assert len(publications) == 6
for span in publications:
    assert span["kind"] == "INTERNAL"
    generation = next(generation for generation, parent in parents.items() if parent[0] == span["traceId"])
    assert (span["traceId"], span["parentSpanId"]) == parents[generation]
    assert span["links"] == [{"traceId": creation[0], "spanId": creation[1]}]
    published = span["attributes"]["outbox.write.result"] == "published"
    assert (span["status"].get("code") == 2) == (not published)
assert collections.Counter(span["traceId"] for span in publications) == {trace: 2 for trace, _ in parents.values()}
assert third_root["endUnixNano"] < min(span["startUnixNano"] for span in publications if span["traceId"] == parents[2][0])
losses = by_phase["redrive-publish-lost"]
assert len(losses) == 2
lost_attempts = {(row["checkpoint"]["trace"]["trace"], row["checkpoint"]["trace"]["span"]): row for row in losses}
assert len(lost_attempts) == 2 and not (lost_attempts.keys() & index.keys())
for loss in losses:
    assert loss["exit"] != 0 and loss["pid"] == loss["checkpoint"]["pid"]
pids = [lost_root["pid"], terminal["completion"]["pid"], final["completion"]["pid"], *(row["pid"] for row in losses)]
assert len(set(pids)) == len(pids) == 5
for completion in (terminal["completion"], final["completion"]):
    assert completion["canonicalCount"] == 1 and completion["relayState"] == "WAITING"
assert {span["resource"]["process.pid"] for span in publications if span["traceId"] == parents[1][0]} == {
    by_phase["redrive-automatic-retry"][0]["pid"], terminal["completion"]["pid"]}
assert {span["resource"]["process.pid"] for span in publications if span["traceId"] == parents[2][0]} == {
    by_phase["redrive-automatic-retry"][1]["pid"], final["completion"]["pid"]}

wires = by_phase["broker-wire"]
inboxes = by_phase["inbox"]
assert len(wires) == len(inboxes) == 4
assert collections.Counter(receipt["outcome"] for receipt in inboxes) == {"APPLIED": 1, "DUPLICATE": 3}
assert [carrier(wire["traceparent"])[0] for wire in wires] == [parents[0][0], parents[1][0], parents[2][0], parents[2][0]]
assert len({wire["traceparent"] for wire in wires}) == 4
for wire, inbox in zip(wires, inboxes):
    assert wire["headerCount"] == 1 and carrier(wire["creation"]) == creation
    producer = index[carrier(wire["traceparent"])]
    assert producer["kind"] == "PRODUCER" and producer["scope"]["name"] == "io.opentelemetry.kafka-clients-0.11"
    assert producer["status"].get("code") != 2
    parent = producer["traceId"], producer["parentSpanId"]
    if parent in lost_attempts:
        assert producer["resource"]["process.pid"] == lost_attempts[parent]["pid"]
    else:
        assert index[parent] in publications
    consumer, = [span for span in spans if span["kind"] == "CONSUMER" and span["traceId"] == producer["traceId"]
                 and span.get("parentSpanId") == producer["spanId"]]
    attempt = index[inbox["trace"]["trace"], inbox["trace"]["span"]]
    assert attempt["name"] == "mq.process" and attempt["kind"] == "INTERNAL"
    assert attempt["traceId"] == consumer["traceId"] and attempt["parentSpanId"] == consumer["spanId"]
    assert attempt["links"] == [{"traceId": creation[0], "spanId": creation[1]}]
    if producer["traceId"] != parents[0][0]:
        assert attempt["traceId"] != creation[0]
    assert attempt["status"].get("code") != 2
assert len([span for span in spans if span["name"] == "mq.process" and span["traceId"] in {trace for trace, _ in parents.values()}]) == 4

logs = [log for log in events(root / "relay-process-logs.jsonl") if log.get("message_id") == identity]
children = root / "redrive-process-children"
for path in children.glob("*/*-logs.jsonl"):
    logs.extend(events(path))
canonical = [log for log in logs if log["log"]["logger"] == "task.execute"]
recoveries = [log for log in logs if log["log"]["logger"] == "outbox.recovery"]
finals = [log for log in logs if log["log"]["logger"] == "outbox.publish.failure"]
assert (len(canonical), len(recoveries), len(finals)) == (6, 4, 2)
assert len({(log["trace_id"], log["span_id"]) for log in canonical}) == 6
for log in logs:
    assert_identity(log, "origin-service")
for log in canonical:
    assert index[log["trace_id"], log["span_id"]] in publications
    assert log["log"]["level"] == "INFO"
    assert log["event"]["outcome"] == ("success" if log["outbox_write_result"] == "published" else "failure")
assert collections.Counter(log["outbox_write_result"] for log in canonical) == {"failed": 1, "terminal": 2, "retry": 2, "published": 1}
root_warnings = [log for log in recoveries if "outbox_failed_attempts" not in log]
assert {(log["trace_id"], log["span_id"]) for log in root_warnings} == {parents[1], parents[2]}
for log in root_warnings:
    assert log["log"]["level"] == "WARN" and "event" not in log and "error" not in log
consume_logs = events(root / "redrive-inbound-logs.jsonl")
assert len(consume_logs) == 4
assert {(log["trace_id"], log["span_id"]) for log in consume_logs} == {(row["trace"]["trace"], row["trace"]["span"]) for row in inboxes}
for log in consume_logs:
    assert_identity(log, "local-consumer")
    assert log["log"] == {"level": "INFO", "logger": "mq.consume"} and log["event"]["outcome"] == "success"
    assert "retry" not in log
for path in children.glob("*/*-output.log"):
    assert "Governed OpenTelemetry Agent extension handshake: PASS" in path.read_text()
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == [] and status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    for path in root.rglob("*"):
        if path.is_file() and path.suffix in (".json", ".jsonl", ".log") and path.name != "sentinels.json":
            assert sentinel not in path.read_text(), path.name
print(json.dumps({"databases": 2, "databaseRedriveRoots": 18, "databaseCommittedWarnings": 12,
                  "processGenerations": 3, "committedRedrives": 2, "lostCommittedRoot": 1, "lostOpenPublishSpans": 2,
                  "freshChildPids": 5, "exportedPublishSpans": 6, "acknowledgedRecords": 4,
                  "agentConsumers": 4, "applicationAttempts": 4, "inboxApplied": 1, "inboxDuplicates": 3,
                  "retainedFailedAttempts": 4, "sensitiveMatches": []}, indent=2))
