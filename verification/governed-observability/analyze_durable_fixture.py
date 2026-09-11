#!/usr/bin/env python3
"""Reconcile two real database upgrades/transactions with actual append spans and persisted pure values."""
import collections
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
assert (root / "durable-agent.exit").read_text().strip() == "0"
receipts = json.loads((root / "durable-receipts.json").read_text())
all_spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
assert len({span["spanId"] for span in all_spans}) == len(all_spans)
native = [span for span in all_spans if span["scope"]["name"] == "MySQL Connector/J"]
spans = [span for span in all_spans if span["scope"]["name"] != "MySQL Connector/J"]
assert native, "Connector/J 9.7.0 defaults to native OpenTelemetry using the same global SDK"
assert len(spans) == 24
assert collections.Counter((span["name"], span["kind"]) for span in spans) == {
    ("fixture.durable", "INTERNAL"): 14, ("outbox.append", "INTERNAL"): 10}
for span in all_spans:
    assert span["resource"]["telemetry.sdk.version"] == "1.65.0"
    assert not span.get("events") and not span["status"].get("description")
    assert not {"db.statement", "db.query.text", "db.query", "exception.message", "exception.stacktrace"} & span["attributes"].keys()
    assert span["endUnixNano"] >= span["startUnixNano"]
for span in spans:
    assert span["links"] == []
for span in native:
    assert span["kind"] == "CLIENT" and span["scope"]["version"] == "9.7.0"
    assert span["attributes"].get("db.system") == "mysql"
append = [span for span in spans if span["name"] == "outbox.append"]
supplied = "00-" + "3" * 32 + "-" + "4" * 16 + "-01"
for database in ("postgresql", "mysql"):
    rows = [row for row in receipts if row["database"] == database]
    callers = {row["operation"]: row for row in rows if row["phase"] == "caller"}
    assert set(callers) == {"commit", "rollback", "duplicate", "supplied", "unsampled", "root",
                            "no-transaction", "read-only", "wrong-source"}
    phases = {row["phase"]: row for row in rows if row["phase"] != "caller"}
    assert phases["migration"]["legacyRows"] == 3 and phases["migration"]["legacyFieldsPreserved"]
    assert not phases["migration"]["normalValidationExecutedDdl"]
    summary = phases["summary"]
    assert {key: summary[key] for key in ("committedMessages", "legacyRows", "wakes", "rollbackRows",
            "rejectedCalls", "claimedMessages", "frameworkResultLogs")} == {
                "committedMessages": 4, "legacyRows": 3, "wakes": 4, "rollbackRows": 0,
                "rejectedCalls": 3, "claimedMessages": 5, "frameworkResultLogs": 0}
    assert summary["independentPublicationMetadata"]
    observed = [span for span in append if span["attributes"]["messaging.message.id"].startswith(database + "-")]
    assert len(observed) == 5
    for operation in ("commit", "rollback", "duplicate", "supplied", "root"):
        message_id = database + "-" + ("commit" if operation == "duplicate" else operation)
        candidates = [span for span in observed if span["attributes"]["messaging.message.id"] == message_id]
        if operation in ("commit", "duplicate"):
            candidates = [span for span in candidates if (span["status"].get("code") == 2) == (operation == "duplicate")]
        assert len(candidates) == 1
        span = candidates[0]
        caller = callers[operation]
        if operation == "root":
            assert not caller["valid"] and not span.get("parentSpanId")
        else:
            assert (span["traceId"], span["parentSpanId"]) == (caller["trace"], caller["span"])
        if operation == "duplicate":
            assert span["attributes"]["error.type"] == "org.springframework.dao.DuplicateKeyException"
            continue
        assert span["status"].get("code") != 2, "append success must not infer outer commit outcome"
        phase = {"commit": "inside-commit", "rollback": "inside-rollback"}.get(operation, operation)
        row = phases[phase]
        assert row["publication_generation"] == 0 and row["message_id"] == message_id
        assert row["correlation_id"] == "original-" + message_id
        assert row["publication_traceparent"] == row["creation_traceparent"]
        assert row["publication_tracestate"] == row["creation_tracestate"]
        assert span["endUnixNano"] <= row["recordedAtNanos"], "short append must end before transaction decision"
        if operation == "supplied":
            assert row["creation_traceparent"] == supplied and row["creation_tracestate"] == "vendor=original"
        else:
            _, trace, identity, flags = row["creation_traceparent"].split("-")
            assert (trace, identity) == (span["traceId"], span["spanId"]) and int(flags, 16) & 1
        if operation == "commit":
            committed = phases["committed"]
            for key in ("creation_traceparent", "creation_tracestate", "publication_traceparent",
                        "publication_tracestate", "publication_generation", "correlation_id", "message_id"):
                assert committed[key] == row[key]
    unsampled = phases["unsampled"]
    _, trace, identity, flags = unsampled["creation_traceparent"].split("-")
    assert trace == callers["unsampled"]["trace"] and identity != callers["unsampled"]["span"] and flags == "00"
    assert unsampled["creation_tracestate"] == unsampled["publication_tracestate"] == "vendor=unsampled"
    assert unsampled["publication_traceparent"] == unsampled["creation_traceparent"]
    assert unsampled["publication_generation"] == 0 and not any(span["traceId"] == trace for span in all_spans)
    for operation in ("no-transaction", "read-only", "wrong-source"):
        assert sum(span["traceId"] == callers[operation]["trace"] for span in spans) == 1
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    assert sentinel not in (root / "durable-agent.log").read_text()
    assert sentinel not in (root / "spans.jsonl").read_text()
    assert sentinel not in (root / "durable-receipts.json").read_text()
print(json.dumps({"databases": 2, "upgradedHistoricalRows": 6, "committedMessages": 8,
                  "rollbackRows": 0, "transactionRejections": 6, "afterCommitWakes": 8,
                  "appendCalls": 12, "sampledAppendSpans": 10, "unsampledAppendCalls": 2,
                  "frameworkAndFixtureSpans": len(spans), "nativeMysqlSpans": len(native),
                  "nativeMysqlErrorSpans": sum(span["status"].get("code") == 2 for span in native),
                  "exportedSpans": len(all_spans), "frameworkResultLogs": 0,
                  "claimMetadataIndependent": True, "sensitiveMatches": []}, indent=2))
