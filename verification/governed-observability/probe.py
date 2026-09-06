#!/usr/bin/env python3
"""Exercise and evaluate governed Agent behavior through public production seams."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


TRACEPARENT = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
VALID_TRACE_ID = "11111111111111111111111111111111"
VALID_PARENT_ID = "2222222222222222"
UNSAMPLED_TRACE_ID = "33333333333333333333333333333333"
UNSAMPLED_PARENT_ID = "4444444444444444"
ORDER_TRACE_ID = "55555555555555555555555555555555"
ORDER_PARENT_ID = "6666666666666666"
CACHE_TRACE_ID = "99999999999999999999999999999999"
CACHE_PARENT_ID = "aaaaaaaaaaaaaaaa"
OUTAGE_TRACE_ID = "77777777777777777777777777777777"
OUTAGE_PARENT_ID = "8888888888888888"


def request(
    base: str,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, str], dict[str, Any]]:
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    data = None if body is None else json.dumps(body).encode("utf-8")
    operation = urllib.request.Request(base + path, data=data, headers=request_headers, method=method)
    try:
        response = urllib.request.urlopen(operation, timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        response_body = response.read()
        parsed = json.loads(response_body) if response_body else {}
        return response.status, dict(response.headers.items()), parsed


def require_status(actual: int, expected: int, label: str) -> None:
    if actual != expected:
        raise AssertionError(f"{label}: expected HTTP {expected}, got {actual}")


def login(base: str) -> str:
    status, _, body = request(
        base,
        "POST",
        "/api/identity/v1/session/login",
        {"username": "alice", "password": "alice-password", "tenantId": "tenant-a"},
    )
    require_status(status, 200, "login")
    token = body.get("token", "")
    if not isinstance(token, str) or len(token) != 43 or "." in token:
        raise AssertionError("login did not return the expected opaque session")
    return token


def create_order(base: str, token: str, traceparent: str) -> tuple[str, float]:
    started = time.monotonic()
    status, _, body = request(
        base,
        "POST",
        "/api/order/v1/orders",
        {"lines": [{"productId": 100, "quantity": 1}]},
        {
            "Authorization": "Bearer " + token,
            "X-Correlation-Id": "governed-agent-" + traceparent[3:11],
            "traceparent": traceparent,
        },
    )
    duration = time.monotonic() - started
    if status != 201:
        raise AssertionError(f"create order: expected HTTP 201, got {status}, response={body!r}")
    order_id = body.get("orderId", "")
    if not isinstance(order_id, str) or not order_id.isdigit():
        raise AssertionError("create order returned an invalid identifier")
    return order_id, duration


def await_terminal(base: str, token: str, order_id: str) -> str:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        status, _, body = request(
            base,
            "GET",
            "/api/order/v1/orders/" + order_id,
            headers={"Authorization": "Bearer " + token},
        )
        if status == 200 and body.get("status") in {"CONFIRMED", "REJECTED"}:
            return body["status"]
        time.sleep(0.25)
    raise AssertionError(f"order {order_id} did not reach a terminal state")


def read_records(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def exactly_one(spans: list[dict[str, Any]], label: str, predicate: Any) -> dict[str, Any]:
    matches = [span for span in spans if predicate(span)]
    if len(matches) != 1:
        raise AssertionError(f"{label}: expected exactly one span, got {len(matches)}")
    return matches[0]


def causally_follows(child: dict[str, Any], parent: dict[str, Any]) -> bool:
    if child.get("traceId") == parent.get("traceId") and child.get("parentSpanId") == parent.get("spanId"):
        return True
    return any(
        link.get("traceId") == parent.get("traceId") and link.get("spanId") == parent.get("spanId")
        for link in child.get("links", [])
    )


def kafka_records(path: Path) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t", 2)
        if len(parts) < 2:
            continue
        header_text, key = parts[0], parts[1]
        traceparent = re.search(r"traceparent:([^,\t]+)", header_text)
        records.append({"key": key, "traceparent": traceparent.group(1) if traceparent else ""})
    return records


def observed_after(path: Path, count: int, expected_path: str) -> dict[str, Any]:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        records = read_records(path)
        matches = [record for record in records[count:] if record.get("path") == expected_path]
        if matches:
            return matches[-1]
        time.sleep(0.05)
    raise AssertionError(f"proxy did not observe {expected_path}")


def validate_outbound(record: dict[str, Any], expected_trace: str | None, expected_flags: str) -> str:
    traceparent = record.get("traceparent")
    match = TRACEPARENT.fullmatch(traceparent or "")
    if not match:
        raise AssertionError(f"outbound traceparent is missing or invalid: {traceparent!r}")
    if expected_trace is not None and match.group(1) != expected_trace:
        raise AssertionError(f"outbound trace id {match.group(1)} did not preserve {expected_trace}")
    if match.group(3) != expected_flags:
        raise AssertionError(f"outbound trace flags {match.group(3)} did not preserve {expected_flags}")
    return traceparent


def wrong_login(
    base: str,
    identity_headers: Path,
    traceparent: str | None,
    sentinels: dict[str, str] | None = None,
) -> dict[str, Any]:
    before = len(read_records(identity_headers))
    body = {"username": "nobody", "password": "wrong", "tenantId": "tenant-a"}
    headers: dict[str, str] = {}
    path = "/api/identity/v1/session/login"
    if traceparent is not None:
        headers["traceparent"] = traceparent
    if sentinels is not None:
        body["username"] = sentinels["payload"]
        body["password"] = sentinels["credential"]
        headers["X-Governed-Sensitive"] = sentinels["header"]
        headers["User-Agent"] = sentinels["header"]
        path += "?probe=" + urllib.parse.quote(sentinels["query"])
    status, _, _ = request(base, "POST", path, body, headers)
    require_status(status, 401, "wrong login")
    return observed_after(identity_headers, before, "/v1/session/login")


def traffic(args: argparse.Namespace) -> None:
    sentinels = json.loads(args.sentinels.read_text(encoding="utf-8"))
    base = args.base.rstrip("/")

    valid_record = wrong_login(
        base,
        args.identity_headers,
        f"00-{VALID_TRACE_ID}-{VALID_PARENT_ID}-01",
    )
    valid = validate_outbound(valid_record, VALID_TRACE_ID, "01")

    invalid_record = wrong_login(base, args.identity_headers, "00-invalid-upstream")
    invalid = invalid_record.get("traceparent", "")
    invalid_match = TRACEPARENT.fullmatch(invalid)
    if not invalid_match or invalid_match.group(1) in {VALID_TRACE_ID, UNSAMPLED_TRACE_ID}:
        raise AssertionError("invalid upstream did not produce an independent valid outbound context")

    missing_record = wrong_login(base, args.identity_headers, None, sentinels)
    if not missing_record.get("queryPresent"):
        raise AssertionError("query-bearing Gateway HTTP client path did not reach Identity")
    missing = missing_record.get("traceparent", "")
    missing_match = TRACEPARENT.fullmatch(missing)
    if not missing_match:
        raise AssertionError("missing upstream did not produce a valid outbound context")

    token = login(base)
    before_order = len(read_records(args.order_headers))
    before_identity = len(read_records(args.identity_headers))
    before_catalog = len(read_records(args.catalog_headers))
    sampled_order, sampled_duration = create_order(
        base,
        token,
        f"00-{ORDER_TRACE_ID}-{ORDER_PARENT_ID}-01",
    )
    sampled_header = observed_after(args.order_headers, before_order, "/v1/orders")
    validate_outbound(sampled_header, ORDER_TRACE_ID, "01")
    sampled_status = await_terminal(base, token, sampled_order)
    identity_after_first = read_records(args.identity_headers)[before_identity:]
    first_token_calls = [
        record for record in identity_after_first if record.get("path") == "/internal/api/v1/service-token"
    ]
    first_catalog_calls = [
        record for record in read_records(args.catalog_headers)[before_catalog:]
        if record.get("path") == "/internal/api/v1/catalog/products/100"
    ]
    if len(first_token_calls) != 1 or len(first_catalog_calls) != 1:
        raise AssertionError(
            f"cold token/catalog calls were not exactly 1/1: {len(first_token_calls)}/{len(first_catalog_calls)}"
        )
    validate_outbound(first_token_calls[0], ORDER_TRACE_ID, "01")
    validate_outbound(first_catalog_calls[0], ORDER_TRACE_ID, "01")
    if first_catalog_calls[0].get("correlationId") != "governed-agent-55555555":
        raise AssertionError("first Catalog call did not preserve its correlation ID")

    before_order = len(read_records(args.order_headers))
    before_identity = len(read_records(args.identity_headers))
    before_catalog = len(read_records(args.catalog_headers))
    cache_order, cache_duration = create_order(
        base,
        token,
        f"00-{CACHE_TRACE_ID}-{CACHE_PARENT_ID}-01",
    )
    cache_header = observed_after(args.order_headers, before_order, "/v1/orders")
    validate_outbound(cache_header, CACHE_TRACE_ID, "01")
    cache_status = await_terminal(base, token, cache_order)
    cache_token_calls = [
        record for record in read_records(args.identity_headers)[before_identity:]
        if record.get("path") == "/internal/api/v1/service-token"
    ]
    cache_catalog_calls = [
        record for record in read_records(args.catalog_headers)[before_catalog:]
        if record.get("path") == "/internal/api/v1/catalog/products/100"
    ]
    if cache_token_calls or len(cache_catalog_calls) != 1:
        raise AssertionError(
            f"cache hit token/catalog calls were not exactly 0/1: {len(cache_token_calls)}/{len(cache_catalog_calls)}"
        )
    validate_outbound(cache_catalog_calls[0], CACHE_TRACE_ID, "01")
    if cache_catalog_calls[0].get("correlationId") != "governed-agent-99999999":
        raise AssertionError("cache-hit Catalog call did not preserve its independent correlation ID")

    before_order = len(read_records(args.order_headers))
    unsampled_order, unsampled_duration = create_order(
        base,
        token,
        f"00-{UNSAMPLED_TRACE_ID}-{UNSAMPLED_PARENT_ID}-00",
    )
    unsampled_header = observed_after(args.order_headers, before_order, "/v1/orders")
    validate_outbound(unsampled_header, UNSAMPLED_TRACE_ID, "00")
    unsampled_status = await_terminal(base, token, unsampled_order)

    result = {
        "validSampledOutbound": valid,
        "invalidUpstreamOutbound": invalid,
        "missingUpstreamOutbound": missing,
        "queryBearingGatewayClient": {
            "downstreamPath": missing_record["path"],
            "queryPresent": missing_record["queryPresent"],
            "outboundTraceparent": missing,
        },
        "sampledOrder": {
            "id": sampled_order,
            "status": sampled_status,
            "httpSeconds": sampled_duration,
            "outboundTraceparent": sampled_header["traceparent"],
        },
        "cacheHitOrder": {
            "id": cache_order,
            "status": cache_status,
            "httpSeconds": cache_duration,
            "correlationId": "governed-agent-99999999",
            "outboundTraceparent": cache_header["traceparent"],
            "identityTokenNetworkCalls": 0,
            "catalogNetworkCalls": 1,
        },
        "coldCacheOrder": {
            "id": sampled_order,
            "correlationId": "governed-agent-55555555",
            "identityTokenNetworkCalls": 1,
            "catalogNetworkCalls": 1,
        },
        "unsampledOrder": {
            "id": unsampled_order,
            "status": unsampled_status,
            "httpSeconds": unsampled_duration,
            "outboundTraceparent": unsampled_header["traceparent"],
        },
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def outage(args: argparse.Namespace) -> None:
    base = args.base.rstrip("/")
    token = login(base)
    order_id, duration = create_order(
        base,
        token,
        f"00-{OUTAGE_TRACE_ID}-{OUTAGE_PARENT_ID}-01",
    )
    status = await_terminal(base, token, order_id)
    if duration >= 5:
        raise AssertionError(f"business HTTP waited {duration:.3f}s for the unavailable telemetry receiver")
    result = {"orderId": order_id, "status": status, "createHttpSeconds": duration}
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def analyze(args: argparse.Namespace) -> None:
    status = json.loads(args.receiver_status.read_text(encoding="utf-8"))
    traffic_result = json.loads(args.traffic.read_text(encoding="utf-8"))
    outage_result = json.loads(args.outage.read_text(encoding="utf-8"))
    spans = read_records(args.spans)

    if status["requests"] < 1 or status["spans"] != len(spans) or not spans:
        raise AssertionError("receiver did not decode the exported OTLP spans")
    if "application/x-protobuf" not in status["contentTypes"]:
        raise AssertionError(f"trace exporter did not use OTLP protobuf: {status['contentTypes']}")
    if status["sensitiveMatches"]:
        raise AssertionError(f"automatic telemetry leaked sensitive sentinel classes: {status['sensitiveMatches']}")

    span_ids = [span.get("spanId") for span in spans]
    if None in span_ids or len(span_ids) != len(set(span_ids)):
        raise AssertionError("exported span IDs are missing or duplicated")
    resources = {span.get("resource", {}).get("service.name") for span in spans}
    required_services = {"gateway", "identity", "catalog", "order", "inventory"}
    if not required_services.issubset(resources):
        raise AssertionError(f"actual Agent spans are missing services: {sorted(required_services - resources)}")
    forbidden_resource_keys = {"process.command_args", "process.command_line"}
    if any(forbidden_resource_keys.intersection(span.get("resource", {})) for span in spans):
        raise AssertionError("automatic telemetry exported raw process command-line resource attributes")
    if any(
        not {"service.name", "telemetry.sdk.name", "telemetry.sdk.version"}.issubset(span.get("resource", {}))
        for span in spans
    ):
        raise AssertionError("resource filtering removed required service or telemetry SDK identity")

    valid_spans = [span for span in spans if span.get("traceId") == VALID_TRACE_ID]
    if not any(
        span.get("kind") == "SERVER"
        and span.get("parentSpanId") == VALID_PARENT_ID
        and span.get("resource", {}).get("service.name") == "gateway"
        for span in valid_spans
    ):
        raise AssertionError("sampled upstream was not accepted as the Gateway server parent")
    if not any(span.get("kind") == "CLIENT" for span in valid_spans):
        raise AssertionError("sampled HTTP path has no Agent-owned client span")
    if not any(
        span.get("kind") == "SERVER" and span.get("resource", {}).get("service.name") == "identity"
        for span in valid_spans
    ):
        raise AssertionError("sampled HTTP propagation has no downstream Identity server span")

    query_traceparent = traffic_result["queryBearingGatewayClient"]["outboundTraceparent"]
    query_match = TRACEPARENT.fullmatch(query_traceparent)
    if not query_match:
        raise AssertionError("query-bearing HTTP path has no valid downstream traceparent evidence")
    query_trace_spans = [span for span in spans if span.get("traceId") == query_match.group(1)]
    if not any(
        span.get("kind") == "CLIENT"
        and span.get("resource", {}).get("service.name") == "gateway"
        for span in query_trace_spans
    ):
        raise AssertionError("query-bearing HTTP path has no Gateway client span")
    if not any(
        span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "identity"
        for span in query_trace_spans
    ):
        raise AssertionError("query-bearing HTTP path has no downstream Identity server span")

    order_spans = [span for span in spans if span.get("traceId") == ORDER_TRACE_ID]
    order_services = {span.get("resource", {}).get("service.name") for span in order_spans}
    if not {"gateway", "order"}.issubset(order_services):
        raise AssertionError(f"sampled order trace did not cross the real Gateway and Order apps: {order_services}")
    if any(span.get("traceId") == UNSAMPLED_TRACE_ID for span in spans):
        raise AssertionError("valid unsampled upstream unexpectedly exported spans")

    sampled_order_id = traffic_result["sampledOrder"]["id"]
    gateway_server = exactly_one(
        order_spans,
        "sampled Gateway order server",
        lambda span: span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "gateway"
        and span.get("attributes", {}).get("http.request.method") == "POST",
    )
    gateway_client = exactly_one(
        order_spans,
        "sampled Gateway to Order client",
        lambda span: span.get("kind") == "CLIENT"
        and span.get("resource", {}).get("service.name") == "gateway"
        and span.get("attributes", {}).get("http.request.method") == "POST"
        and span.get("attributes", {}).get("url.full", "").endswith("/v1/orders"),
    )
    order_server = exactly_one(
        order_spans,
        "sampled Order server",
        lambda span: span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "order"
        and span.get("attributes", {}).get("http.route") == "/v1/orders",
    )
    if not causally_follows(gateway_client, gateway_server) or not causally_follows(order_server, gateway_client):
        raise AssertionError("sampled Gateway to Order HTTP spans are not causally connected")
    identity_client = exactly_one(
        order_spans,
        "cold-cache Order to Identity client",
        lambda span: span.get("kind") == "CLIENT"
        and span.get("resource", {}).get("service.name") == "order"
        and span.get("attributes", {}).get("url.full", "").endswith("/internal/api/v1/service-token"),
    )
    identity_server = exactly_one(
        order_spans,
        "cold-cache Identity token server",
        lambda span: span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "identity"
        and span.get("attributes", {}).get("http.route") == "/internal/api/v1/service-token",
    )
    catalog_client = exactly_one(
        order_spans,
        "sampled Order to Catalog client",
        lambda span: span.get("kind") == "CLIENT"
        and span.get("resource", {}).get("service.name") == "order"
        and "/internal/api/v1/catalog/products/100" in span.get("attributes", {}).get("url.full", ""),
    )
    catalog_server = exactly_one(
        order_spans,
        "sampled Catalog server",
        lambda span: span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "catalog"
        and span.get("attributes", {}).get("http.route") == "/internal/api/v1/catalog/products/{productId}",
    )
    for label, child, parent in (
        ("Order to Identity", identity_client, order_server),
        ("Identity server", identity_server, identity_client),
        ("Order to Catalog", catalog_client, order_server),
        ("Catalog server", catalog_server, catalog_client),
    ):
        if not causally_follows(child, parent):
            raise AssertionError(f"{label} span is not causally connected to the sampled operation")

    reserve_producer = exactly_one(
        spans,
        "sampled reserve producer",
        lambda span: span.get("kind") == "PRODUCER"
        and span.get("resource", {}).get("service.name") == "order"
        and span.get("attributes", {}).get("messaging.kafka.message.key") == sampled_order_id,
    )
    inventory_consumers = [
        span for span in spans
        if span.get("kind") == "CONSUMER"
        and span.get("resource", {}).get("service.name") == "inventory"
        and causally_follows(span, reserve_producer)
    ]
    if len(inventory_consumers) != 1:
        raise AssertionError(f"sampled reserve consumer count is {len(inventory_consumers)}, expected 1")
    inventory_consumer = inventory_consumers[0]
    result_producer = exactly_one(
        spans,
        "sampled inventory result producer",
        lambda span: span.get("kind") == "PRODUCER"
        and span.get("resource", {}).get("service.name") == "inventory"
        and span.get("attributes", {}).get("messaging.kafka.message.key") == f"tenant-a:{sampled_order_id}",
    )
    if not causally_follows(result_producer, inventory_consumer):
        raise AssertionError("sampled result producer is not causally connected to the reserve consumer")
    order_consumers = [
        span for span in spans
        if span.get("kind") == "CONSUMER"
        and span.get("resource", {}).get("service.name") == "order"
        and causally_follows(span, result_producer)
    ]
    if len(order_consumers) != 1:
        raise AssertionError(f"sampled result consumer count is {len(order_consumers)}, expected 1")
    operation_spans = [
        gateway_server,
        gateway_client,
        order_server,
        identity_client,
        identity_server,
        catalog_client,
        catalog_server,
        reserve_producer,
        inventory_consumer,
        result_producer,
        order_consumers[0],
    ]
    if any(not span.get("scope", {}).get("name", "").startswith("io.opentelemetry.") for span in operation_spans):
        raise AssertionError("sampled operation includes a span without an Agent instrumentation owner")

    cache_spans = [span for span in spans if span.get("traceId") == CACHE_TRACE_ID]
    exactly_one(
        cache_spans,
        "cache-hit Order to Catalog client",
        lambda span: span.get("kind") == "CLIENT"
        and span.get("resource", {}).get("service.name") == "order"
        and "/internal/api/v1/catalog/products/100" in span.get("attributes", {}).get("url.full", ""),
    )
    exactly_one(
        cache_spans,
        "cache-hit Catalog server",
        lambda span: span.get("kind") == "SERVER"
        and span.get("resource", {}).get("service.name") == "catalog"
        and span.get("attributes", {}).get("http.route") == "/internal/api/v1/catalog/products/{productId}",
    )
    if any(span.get("attributes", {}).get("http.route") == "/internal/api/v1/service-token" for span in cache_spans):
        raise AssertionError("cache-hit trace unexpectedly called the Identity service-token endpoint")

    kinds = {span.get("kind") for span in operation_spans}
    forbidden_keys = {"db.statement", "db.query.text", "url.query"}
    observed_forbidden = sorted(
        {
            key
            for span in spans
            for key in span.get("attributes", {})
            if key in forbidden_keys
        }
    )
    if observed_forbidden:
        raise AssertionError(f"automatic telemetry emitted forbidden raw fields: {observed_forbidden}")
    query_bearing_urls = sorted(
        {
            value
            for span in spans
            for key, value in span.get("attributes", {}).items()
            if key in {"url.full", "http.url"}
            and isinstance(value, str)
            and urllib.parse.urlsplit(value).query
        }
    )
    if query_bearing_urls:
        raise AssertionError("automatic telemetry emitted a URL containing a query")

    records = kafka_records(args.kafka_records)
    sampled_reserve_records = [record for record in records if record["key"] == sampled_order_id]
    sampled_result_records = [record for record in records if record["key"] == f"tenant-a:{sampled_order_id}"]
    if len(sampled_reserve_records) != 1 or len(sampled_result_records) != 1:
        raise AssertionError("sampled operation Kafka keys were not observed exactly once")
    for record in sampled_reserve_records + sampled_result_records:
        match = TRACEPARENT.fullmatch(record["traceparent"])
        if not match or match.group(1) != ORDER_TRACE_ID:
            raise AssertionError("sampled operation Kafka record did not preserve its actual Trace")
    if outage_result.get("status") not in {"CONFIRMED", "REJECTED"}:
        raise AssertionError("receiver-outage order did not retain its business terminal result")
    outage_order_id = outage_result["orderId"]
    outage_reserve_records = [record for record in records if record["key"] == outage_order_id]
    outage_result_records = [record for record in records if record["key"] == f"tenant-a:{outage_order_id}"]
    if len(outage_reserve_records) != 1 or len(outage_result_records) != 1:
        raise AssertionError("receiver-outage Kafka records were not observed exactly once in the controlled run")
    expected_order_database = f"1|{outage_result['status']}|1|PUBLISHED|0|1"
    if args.outage_order_database.read_text(encoding="utf-8").strip() != expected_order_database:
        raise AssertionError("receiver-outage Order evidence does not show one effective controlled outcome")
    inventory_result = "RESERVED" if outage_result["status"] == "CONFIRMED" else "REJECTED"
    expected_inventory_database = f"1|{inventory_result}|1|1|PUBLISHED|0"
    if args.outage_inventory_database.read_text(encoding="utf-8").strip() != expected_inventory_database:
        raise AssertionError("receiver-outage Inventory evidence does not show one effective controlled outcome")

    owners: dict[str, int] = {}
    for span in spans:
        owner = span.get("scope", {}).get("name", "unknown")
        owners[owner] = owners.get(owner, 0) + 1
    report = {
        "agentOwnedServices": sorted(service for service in resources if service),
        "exportedSpanCount": len(spans),
        "instrumentationOwners": dict(sorted(owners.items())),
        "sampledOperationAgentSpanCount": len(operation_spans),
        "sampledOperationKafkaRecords": len(sampled_reserve_records) + len(sampled_result_records),
        "kinds": sorted(kind for kind in kinds if kind),
        "otlpRequests": status["requests"],
        "payloadBytesScanned": status["payloadBytesScanned"],
        "sensitiveSentinelMatches": status["sensitiveMatches"],
        "queryBearingGatewayClient": traffic_result["queryBearingGatewayClient"],
        "sampledOrder": traffic_result["sampledOrder"],
        "cacheProof": {
            "cold": traffic_result["coldCacheOrder"],
            "hit": traffic_result["cacheHitOrder"],
        },
        "unsampledOrder": traffic_result["unsampledOrder"],
        "receiverOutageOrder": outage_result,
    }
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    traffic_command = commands.add_parser("traffic")
    traffic_command.add_argument("--base", required=True)
    traffic_command.add_argument("--identity-headers", type=Path, required=True)
    traffic_command.add_argument("--order-headers", type=Path, required=True)
    traffic_command.add_argument("--catalog-headers", type=Path, required=True)
    traffic_command.add_argument("--sentinels", type=Path, required=True)
    traffic_command.add_argument("--output", type=Path, required=True)
    traffic_command.set_defaults(run=traffic)
    outage_command = commands.add_parser("outage")
    outage_command.add_argument("--base", required=True)
    outage_command.add_argument("--output", type=Path, required=True)
    outage_command.set_defaults(run=outage)
    analyze_command = commands.add_parser("analyze")
    analyze_command.add_argument("--receiver-status", type=Path, required=True)
    analyze_command.add_argument("--spans", type=Path, required=True)
    analyze_command.add_argument("--traffic", type=Path, required=True)
    analyze_command.add_argument("--outage", type=Path, required=True)
    analyze_command.add_argument("--kafka-records", type=Path, required=True)
    analyze_command.add_argument("--outage-order-database", type=Path, required=True)
    analyze_command.add_argument("--outage-inventory-database", type=Path, required=True)
    analyze_command.add_argument("--output", type=Path, required=True)
    analyze_command.set_defaults(run=analyze)
    return root


def main() -> None:
    args = parser().parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
