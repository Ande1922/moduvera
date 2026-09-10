#!/usr/bin/env python3
"""Reconcile actual HTTP receipts, parent-context result logs and Agent-owned spans."""
import collections
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

root = Path(sys.argv[1])
raw = (root / "http-outbound-agent.log").read_text()
assert (root / "http-outbound-agent.exit").read_text().strip() == "0"
assert raw.count("Governed OpenTelemetry Agent extension handshake: PASS") == 2
records = []
for line in raw.splitlines():
    try:
        record = json.loads(line)
        if isinstance(record, dict):
            records.append(record)
    except ValueError:
        pass
events = [row for row in records if "log" in row]
results = [row for row in events if row["log"].get("logger") == "http.client"]
receipts = [row["http_fixture_receipt"] for row in records if "http_fixture_receipt" in row]
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
clients = {span["spanId"]: span for span in spans if span["kind"] == "CLIENT"}
servers = [span for span in spans if span["kind"] == "SERVER"]
assert len(results) == 18 and len(receipts) == 17
assert len(clients) == 14 and len(servers) == 13 and len(spans) == 27
assert collections.Counter(row["event"]["outcome"] for row in results) == {"success": 10, "failure": 7, "unknown": 1}
assert collections.Counter(row["target"] for row in results) == {"identity-service": 12, "catalog-service": 6}
by_receipt = {(row["correlation"], row["target"], row["path"]): row for row in receipts}
assert len(by_receipt) == len(receipts), "unexpected extra wire requests or duplicate receipts"
result_keys = {(row["correlation_id"], row["target"], row["url"]["path"]) for row in results}
assert len(result_keys) == len(results), "duplicate observable-attempt result"
assert len({row["correlation_id"] for row in results}) == 14
matched_clients = set()
unsampled = 0
connection_failures = 0
for row in results:
    assert row["log"]["level"] == "INFO" and row["duration_ms"] >= 0
    assert re.fullmatch(r"[0-9a-f]{32}", row["trace_id"]) and row["trace_id"] != "0" * 32
    assert row["span_id"] == "1234567890abcdef", "result must retain supplied operation Context"
    assert "?" not in row["url"]["path"] and "query" not in row["url"]
    assert "retry" not in row and "stack_trace" not in row.get("error", {}) and "message" not in row.get("error", {})
    receipt = by_receipt.get((row["correlation_id"], row["target"], row["url"]["path"]))
    if receipt is None:
        connection_failures += 1
        assert row["url"]["path"] == "/internal/api/v1/token/exchange"
        assert row["event"]["outcome"] == "failure" and "response" not in row["http"]
        candidates = [span for span in clients.values() if span["traceId"] == row["trace_id"]
                      and urlsplit(span["attributes"]["url.full"]).path == row["url"]["path"]]
        assert len(candidates) == 1
        client = candidates[0]
        assert not any(server["parentSpanId"] == client["spanId"] for server in servers)
    else:
        version, trace, span_id, flags = receipt["traceparent"].split("-")
        assert version == "00" and trace == row["trace_id"] and receipt["tracestate"] == "fixture=retained"
        if flags == "00":
            unsampled += 1
            assert not any(span["traceId"] == trace for span in spans), "unsampled Context must propagate without export"
            continue
        assert flags == "01"
        client = clients[span_id]
        children = [server for server in servers if server["parentSpanId"] == span_id and server["traceId"] == trace]
        assert len(children) == 1, "one actual server owner per received sampled request"
        assert children[0]["traceState"] == "fixture=retained"
    assert client["traceId"] == row["trace_id"] and client["parentSpanId"] == row["span_id"]
    assert client["traceState"] == "fixture=retained"
    assert client["resource"]["telemetry.sdk.version"] == "1.65.0"
    matched_clients.add(client["spanId"])
assert matched_clients == set(clients) and unsampled == 4 and connection_failures == 1
# The same tenant/initiator reuses one token across two C values and two Trace Contexts.
for first_trace, second_trace in [("1" * 32, "7" * 32), ("2" * 32, "8" * 32)]:
    first = [row for row in results if row["trace_id"] == first_trace]
    second = [row for row in results if row["trace_id"] == second_trace]
    assert collections.Counter(row["target"] for row in first) == {"identity-service": 1, "catalog-service": 1}
    assert len(second) == 1 and second[0]["target"] == "catalog-service"
    assert {row["tenant_id"] for row in first + second} in [{"cache-01"}, {"cache-00"}]
    assert len({row["correlation_id"] for row in first + second}) == 2
assert not any(row["log"]["level"] in ("ERROR", "WARN") for row in events)
platform = [line for line in raw.splitlines() if " ERROR io.netty.resolver.dns.DnsServerAddressStreamProviders -- Unable to load "
            "io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider, fallback to system defaults." in line]
other_errors = [line for line in raw.splitlines() if re.search(r"\bERROR\b", line) and line not in platform]
assert not other_errors, "unexpected raw ERROR outside the disclosed platform initialization diagnostic"
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == [] and status["postRequestsBySignal"]["logs"] == status["postRequestsBySignal"]["metrics"] == 0
for value in json.loads((root / "sentinels.json").read_text()).values():
    assert not value or value not in raw, "sensitive content in stdout"
print(json.dumps({"httpResults": len(results), "wireReceipts": len(receipts), "sampledClientSpans": len(clients),
                  "sampledServerSpans": len(servers), "unsampledResults": unsampled,
                  "observedConnectionFailureWithoutServer": connection_failures,
                  "outcomes": dict(collections.Counter(row["event"]["outcome"] for row in results)),
                  "requestErrors": 0, "sensitiveMatches": [], "platformInitializationDiagnostics": platform,
                  "contextBoundary": "actual Agent propagator supplies operation Context; Agent alone creates HTTP spans",
                  "cancellationScope": "actual first-body-buffer cancellation; earlier pre-body library race separately probed at baseline"}, indent=2))
