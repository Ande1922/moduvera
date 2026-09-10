#!/usr/bin/env python3
"""Match Gateway completion and ERROR events with real exported server Spans."""
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
spans = [json.loads(line) for line in (root / "spans.jsonl").read_text().splitlines() if line]
events = []
for line in (root / "gateway-agent.log").read_text().splitlines():
    try:
        event = json.loads(line)
        if isinstance(event, dict) and "log" in event:
            events.append(event)
    except ValueError:
        pass
canonical = [event for event in events if event["log"].get("logger") == "http.request"]
assert canonical, "no Gateway canonical output"
assert len({event["correlation_id"] for event in canonical}) == len(canonical), "duplicate request completion"
servers = {span["spanId"]: span for span in spans if span["kind"] == "SERVER"}
known_trace = "abcdef0123456789abcdef0123456789"
known_parent = "abcdef0123456789"
unsampled = []
for event in canonical:
    assert event["log"]["level"] == "INFO" and event["duration_ms"] >= 0
    assert len(event["trace_id"]) == 32 and len(event["span_id"]) == 16
    assert not any(field in event for field in ("tenant_id", "actor_id", "actor_type", "user_id", "initiator_id")), \
        "Gateway must not infer identity from opaque exchanged credentials or untrusted headers"
    server = servers.get(event["span_id"])
    if server is None:
        assert event["url"]["path"] == "/lifecycle/scheduled/trace-1" and event["trace_id"] == known_trace
        unsampled.append(event)
    else:
        assert server["traceId"] == event["trace_id"]
        assert server["resource"]["telemetry.sdk.version"] == "1.65.0"
        if event["url"]["path"] == "/lifecycle/scheduled/trace-0":
            assert server["traceId"] == known_trace and server["parentSpanId"] == known_parent
assert len(unsampled) == 1
assert len([span for span in spans if span["traceId"] == known_trace]) == 1, \
    "sampled parent must have exactly one Gateway server Span and unsampled parent must not export"
assert len([event for event in canonical if event["url"]["path"].startswith("/lifecycle/pending/")]) == 2
for event in canonical:
    assert event["client"]["ip"] in ("127.0.0.1", "0:0:0:0:0:0:0:1")
    if event["url"]["path"].startswith("/lifecycle/pending/"):
        assert "user_agent" not in event
        assert event["event"]["outcome"] in ("failure", "unknown")
        assert event["http"]["response"]["status_code"] == 200
errors = [event for event in events if event["log"].get("level") == "ERROR"]
owned = [event for event in errors if event["log"]["logger"] ==
         "io.github.ande1922.moduvera.reference.app.gateway.GatewayRequestDiagnostics"]
by_correlation = {event["correlation_id"]: event for event in canonical}
for event in owned:
    completion = by_correlation[event["correlation_id"]]
    for field in ("correlation_id", "trace_id", "span_id"):
        assert event[field] == completion[field]
    assert "duration_ms" not in event and "event" not in event
status = json.loads((root / "receiver-status.json").read_text())
assert status["sensitiveMatches"] == []
assert status["postRequestsBySignal"]["logs"] == 0 and status["postRequestsBySignal"]["metrics"] == 0
assert len([event for event in owned if event["error"]["code"] == "SYS_GATEWAY_CORRELATION_MISMATCH"]) == 1
assert len([event for event in owned if event["error"]["code"] == "SYS_UNEXPECTED"]) == 2
assert len(owned) == 3
assert len({event["correlation_id"] for event in owned}) == 3, "duplicate final-error ownership"
assert len([event for event in owned if by_correlation[event["correlation_id"]]["url"]["path"].startswith(
    "/lifecycle/pending/")]) == 1, "unexpected post-commit failure must have one final ERROR; reset has none"
metadata = [event for event in canonical if event["url"]["path"] == "/lifecycle/scheduled/metadata"]
assert len(metadata) == 2
assert {event["user_agent"]["original"] for event in metadata} == {
    "gateway-fixture/1.0", "gateway-fixture credential=[REDACTED]"}
platform = [event for event in errors if event["log"]["logger"] ==
            "io.netty.resolver.dns.DnsServerAddressStreamProviders" and event.get("message", "").startswith(
                "Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider, fallback to system defaults.")]
native = [event for event in errors if event not in owned and event not in platform]
assert not native, "unexpected request ERROR outside Gateway's handled failure fixtures"
sentinels = json.loads((root / "sentinels.json").read_text())
for event in events:
    rendered = json.dumps(event, ensure_ascii=False)
    assert not any(value and value in rendered for value in sentinels.values()), "sensitive sentinel in runtime log"
print(json.dumps({"canonical": len(canonical), "matchedGatewayServerSpans": len(canonical) - len(unsampled),
                  "allExportedServerSpansIncludingDownstreamFixtures": len(servers), "unsampled": len(unsampled),
                  "gatewayErrors": len(owned), "nativeErrors": len(native), "sensitiveMatches": [],
                  "platformInitializationDiagnostics": [{"logger": event["log"]["logger"],
                      "message": event["message"]} for event in platform],
                  "scope": "Gateway WebFlux lifecycle; downstream JDK fixtures are additional server spans"}, indent=2))
