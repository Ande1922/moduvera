#!/usr/bin/env python3
"""Reconcile committed preparation metadata, actual SDK anchors and original-message recovery logs."""
import collections
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
assert (root / "preparation-agent.exit").read_text().strip() == "0"
receipts = json.loads((root / "preparation-receipts.json").read_text())
logs = [json.loads(line) for line in (root / "preparation-logs.jsonl").read_text().splitlines() if line]
all_spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
assert len({span["spanId"] for span in all_spans}) == len(all_spans)
native = [span for span in all_spans if span["scope"]["name"] == "MySQL Connector/J"]
owned = [span for span in all_spans if span["scope"]["name"] != "MySQL Connector/J"]
assert native, "default Connector/J native spans must remain independently accounted for"
assert collections.Counter((span["name"], span["kind"]) for span in owned) == {
    ("fixture.preparation", "INTERNAL"): 44, ("outbox.prepare", "INTERNAL"): 16}
for span in all_spans:
    assert span["resource"]["telemetry.sdk.version"] == "1.65.0"
    assert not span.get("events") and not span["status"].get("description")
    assert not {"db.statement", "db.query.text", "db.query", "exception.message", "exception.stacktrace"} & span["attributes"].keys()
    assert span["endUnixNano"] >= span["startUnixNano"]
for span in native:
    assert span["kind"] == "CLIENT" and span["scope"]["version"] == "9.7.0"
    assert span["attributes"].get("db.system") == "mysql"
preparations = {span["attributes"]["messaging.message.id"]: span for span in owned if span["name"] == "outbox.prepare"}
assert len(preparations) == 16, "repeated/concurrent same-claim preparation must not create another anchor"
assert len({span["traceId"] for span in preparations.values()}) == 16
assert len(logs) == 10
creation = "00-" + "6" * 32 + "-" + "7" * 16 + "-01"
existing = "00-" + "8" * 32 + "-" + "9" * 16 + "-01"
for database in ("postgresql", "mysql"):
    rows = {row["phase"]: row for row in receipts if row["database"] == database}
    assert len(rows) == 14
    assert rows["summary"] == {**rows["summary"], "governed": True, "recoveryLogs": 5,
                               "actualDatabase": True, "transportIsCommitProbe": True}
    assert all(rows["rejections"][key] for key in ("stale", "callerTransaction", "noTransaction", "unchanged"))
    for operation in ("missing", "corrupt", "invalid-creation", "worker", "rollback", "concurrent", "write-failure", "expired"):
        message_id = database + "-" + operation
        span = preparations[message_id]
        assert not span.get("parentSpanId"), "management and corrupt contexts must never become repair parents"
        links = [] if operation in ("missing", "invalid-creation") else [{
            "traceId": "6" * 32, "spanId": "7" * 16, "traceState": "vendor=creation"}]
        assert span["links"] == links
        row = rows["send" if operation == "worker" else operation]
        assert row["message_id"] == message_id and row["correlation_id"] == "original-" + message_id
        assert row["publication_generation"] == (0 if operation == "missing" else 7)
        assert row["attempt_count"] == (3 if operation in ("rollback", "write-failure") else 2)
        if operation == "missing":
            assert row["creation_traceparent"] is None and row["creation_tracestate"] is None
        elif operation == "invalid-creation":
            assert row["creation_traceparent"] == "invalid-creation"
        else:
            assert row["creation_traceparent"] == creation and row["creation_tracestate"] == "vendor=creation"
        committed = operation in ("missing", "corrupt", "invalid-creation", "worker", "concurrent")
        if committed:
            _, trace, identity, flags = row["publication_traceparent"].split("-")
            assert (trace, identity) == (span["traceId"], span["spanId"]) and int(flags, 16) & 1
            assert row["publication_tracestate"] is None
            assert span["endUnixNano"] <= row["recordedAtNanos"], "the short anchor must end before send/later read"
            assert span["status"].get("code") != 2
            matches = [log for log in logs if log["message_id"] == message_id]
            assert len(matches) == 1
            log = matches[0]
            assert log["log"] == {"level": "WARN", "logger": "outbox.recovery"}
            assert log["event"]["action"] == "outbox.publication.repair"
            assert "连续性已中断" in log["message"] and "持久修复" in log["message"]
            assert (log["trace_id"], log["span_id"]) == (span["traceId"], span["spanId"])
            assert log["correlation_id"] == "original-" + message_id and log["tenant_id"] == "origin-tenant"
            assert (log["actor_type"], log["actor_id"]) == ("SERVICE", "origin-service")
            assert (log["initiator_type"], log["initiator_id"]) == ("USER", "origin-user")
            assert "error" not in log and "retry" not in log
        else:
            assert row["publication_traceparent"] == (None if operation == "expired" else "invalid-parent")
            assert not any(log["message_id"] == message_id for log in logs), "no recovery fact for an uncommitted candidate"
            if operation != "expired":
                assert span["status"].get("code") == 2 and span["attributes"].get("error.type")
    for operation in ("existing", "bad-state", "unsampled", "replacement"):
        row = rows[operation]
        expected = existing[:-2] + "00" if operation == "unsampled" else existing
        assert row["publication_traceparent"] == expected and row["publication_generation"] == 7
        assert row["creation_traceparent"] == creation and row["attempt_count"] == 2
        assert database + "-" + operation not in preparations
    assert rows["existing"]["publication_tracestate"] == "vendor=publication"
    assert rows["bad-state"]["publication_tracestate"] == "invalid state"
    assert rows["unsampled"]["publication_tracestate"] == "vendor=unsampled"
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for sentinel in json.loads((root / "sentinels.json").read_text()).values():
    for name in ("preparation-agent.log", "preparation-receipts.json", "preparation-logs.jsonl", "spans.jsonl"):
        assert sentinel not in (root / name).read_text(), name
print(json.dumps({"databases": 2, "committedRepairs": 10, "repairSpans": 16,
                  "rollbackOrWriteFailureCandidates": 4, "expiredCasCandidates": 2,
                  "recoveryWarnings": len(logs), "committedStateTransportProbes": 2,
                  "frameworkAndFixtureSpans": len(owned), "nativeMysqlSpans": len(native),
                  "nativeMysqlErrorSpans": sum(span["status"].get("code") == 2 for span in native),
                  "exportedSpans": len(all_spans), "sensitiveMatches": []}, indent=2))
