#!/usr/bin/env python3
"""Public-HTTP-only acceptance for the local reference product. Never prints credentials or tokens."""

from __future__ import annotations

import concurrent.futures
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request


BASE = os.environ.get(
    "REFERENCE_GATEWAY_BASE",
    sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080",
).rstrip("/")
TOPOLOGY = os.environ.get("REFERENCE_TOPOLOGY", "unspecified")


class Response:
    def __init__(self, status: int, headers, body: bytes):
        self.status = status
        self.headers = headers
        self.body = body.decode("utf-8")

    def json(self):
        return json.loads(self.body)


def request(method: str, path: str, body=None, session=None, correlation=None) -> Response:
    headers = {"Content-Type": "application/json"}
    if session:
        headers["Authorization"] = "Bearer " + session
    if correlation:
        headers["X-Correlation-Id"] = correlation
    data = None if body is None else json.dumps(body).encode("utf-8")
    call = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=10) as result:
            return Response(result.status, result.headers, result.read())
    except urllib.error.HTTPError as failure:
        return Response(failure.code, failure.headers, failure.read())


def expect(response: Response, status: int, label: str) -> Response:
    if response.status != status:
        raise AssertionError(f"{label}: expected HTTP {status}, got {response.status}")
    return response


def header(response: Response, name: str) -> str | None:
    """Read headers from urllib's case-insensitive mapping and lightweight test doubles."""
    direct = response.headers.get(name)
    if direct is not None:
        return direct
    lowered = name.lower()
    return next(
        (value for key, value in response.headers.items() if key.lower() == lowered),
        None,
    )


def expect_correlation(response: Response, correlation: str, label: str) -> None:
    if header(response, "X-Correlation-Id") != correlation:
        raise AssertionError(f"{label}: correlation id was not preserved")


def expect_native_json(response: Response, label: str) -> dict:
    content_type = (header(response, "Content-Type") or "").split(";", 1)[0]
    if content_type != "application/json":
        raise AssertionError(f"{label}: expected application/json, got {content_type or 'missing'}")
    value = response.json()
    if not isinstance(value, dict) or "data" in value or "success" in value:
        raise AssertionError(f"{label}: success response was wrapped instead of native JSON")
    return value


def expect_problem(
    response: Response,
    status: int,
    code: str,
    label: str,
    correlation: str | None = None,
) -> dict:
    expect(response, status, label)
    content_type = (header(response, "Content-Type") or "").split(";", 1)[0]
    if content_type != "application/problem+json":
        raise AssertionError(
            f"{label}: expected application/problem+json, got {content_type or 'missing'}"
        )
    problem = response.json()
    required = {"type", "title", "status", "detail", "code"}
    missing = sorted(required.difference(problem))
    if missing:
        raise AssertionError(f"{label}: RFC 9457 fields missing: {missing}")
    if problem["status"] != status or problem["code"] != code:
        raise AssertionError(
            f"{label}: expected problem {status}/{code}, got "
            f"{problem.get('status')}/{problem.get('code')}"
        )
    if problem["type"] != "urn:problem:" + code:
        raise AssertionError(f"{label}: problem type does not identify {code}")
    if correlation is not None:
        expect_correlation(response, correlation, label)
        body_correlation = problem.get("correlationId")
        if body_correlation is not None and body_correlation != correlation:
            raise AssertionError(f"{label}: problem correlation does not match the response")
    return problem


def expect_order_location(response: Response, order_id: str, label: str) -> None:
    expected = "/api/order/v1/orders/" + order_id
    if header(response, "Location") != expected:
        raise AssertionError(
            f"{label}: expected external Location {expected}, got {header(response, 'Location')}"
        )


def login(username: str, password: str, tenant: str) -> str:
    correlation = "reference-login-" + username
    response = expect(
        request(
            "POST",
            "/api/identity/v1/session/login",
            {"username": username, "password": password, "tenantId": tenant},
            correlation=correlation,
        ),
        200,
        "login " + username,
    )
    value = expect_native_json(response, "login " + username)
    expect_correlation(response, correlation, "login " + username)
    token = value.get("token", "")
    if len(token) != 43 or "." in token:
        raise AssertionError("login returned an invalid opaque session")
    return token


def create(session: str, product: int, quantity: int, correlation: str) -> tuple[str, Response]:
    response = expect(
        request(
            "POST",
            "/api/order/v1/orders",
            {"lines": [{"productId": product, "quantity": quantity}]},
            session,
            correlation,
        ),
        201,
        "create order " + correlation,
    )
    value = expect_native_json(response, "create order " + correlation)
    order_id = value.get("orderId", "")
    if not order_id.isdigit():
        raise AssertionError("create order returned an invalid string identifier")
    if value.get("status") != "PENDING_STOCK":
        raise AssertionError("create order did not return its initial business status")
    expect_correlation(response, correlation, "create order " + correlation)
    expect_order_location(response, order_id, "create order " + correlation)
    return order_id, response


def get_order(session: str, order_id: str, correlation: str) -> Response:
    return request(
        "GET",
        "/api/order/v1/orders/" + order_id,
        session=session,
        correlation=correlation,
    )


def eventually_status(
    session: str,
    order_id: str,
    expected: str | set[str],
    timeout: float = 45,
) -> dict:
    expected_statuses = {expected} if isinstance(expected, str) else expected
    deadline = time.monotonic() + timeout
    last = "unavailable"
    while time.monotonic() < deadline:
        response = get_order(session, order_id, "reference-poll-" + order_id)
        if response.status == 200:
            value = expect_native_json(response, "poll order " + order_id)
            expect_correlation(response, "reference-poll-" + order_id, "poll order " + order_id)
            last = value.get("status", "missing")
            if last in expected_statuses:
                return value
        else:
            last = "HTTP " + str(response.status)
        time.sleep(0.25)
    raise AssertionError(
        f"order {order_id} did not reach {sorted(expected_statuses)} before timeout; "
        f"last state={last}"
    )


def acceptance() -> None:
    expect_problem(
        request(
            "POST",
            "/api/identity/v1/session/login",
            {"username": "alice", "password": "wrong", "tenantId": "tenant-a"},
            correlation="reference-wrong-credentials",
        ),
        401,
        "identity.invalid-credentials",
        "wrong credentials",
        correlation="reference-wrong-credentials",
    )
    expect_problem(
        request(
            "POST",
            "/api/order/v1/orders",
            {"lines": [{"productId": 100, "quantity": 1}]},
            correlation="reference-missing-session",
        ),
        401,
        "gateway.session-required",
        "missing session",
        correlation="reference-missing-session",
    )
    hidden_routes = (
        ("GET", "/internal/api/v1/catalog/products/100", None),
        ("POST", "/internal/api/v1/token/exchange", {}),
        ("POST", "/internal/api/v1/service-token", {}),
        ("GET", "/oauth2/jwks", None),
        ("GET", "/api/catalog/internal/api/v1/catalog/products/100", None),
        ("GET", "/api/order/internal/api/v1/catalog/products/100", None),
        ("GET", "/api/order/actuator/health", None),
        ("POST", "/api/identity/internal/api/v1/token/exchange", {}),
        ("POST", "/api/identity/internal/api/v1/service-token", {}),
        ("GET", "/api/identity/oauth2/jwks", None),
        ("GET", "/api/identity/actuator/health", None),
        ("GET", "/api/v1/orders/1", None),
        ("GET", "/api/order/orders/1", None),
        ("POST", "/api/identity/session/login", {}),
        ("GET", "/api/unknown/v1/orders/1", None),
    )
    for method, path, body in hidden_routes:
        expect(request(method, path, body), 404, "hidden route " + path)

    alice = login("alice", "alice-password", "tenant-a")
    bob = login("bob", "bob-password", "tenant-b")
    viewer = login("viewer", "viewer-password", "tenant-a")

    expect_problem(
        request(
            "POST",
            "/api/order/v1/orders",
            {"lines": [{"productId": 100, "quantity": 0}]},
            alice,
            "reference-validation",
        ),
        400,
        "request.validation-failed",
        "validation",
        correlation="reference-validation",
    )
    expect_problem(
        request(
            "POST",
            "/api/order/v1/orders",
            {"lines": [{"productId": 100, "quantity": 1}]},
            viewer,
            "reference-permission",
        ),
        403,
        "security.permission-denied",
        "permission",
        correlation="reference-permission",
    )
    expect_problem(
        get_order(alice, "1", "reference-not-found"),
        404,
        "order.not-found",
        "same-tenant missing order",
        correlation="reference-not-found",
    )

    confirmed, _ = create(alice, 100, 2, "reference-confirmed")
    eventually_status(alice, confirmed, "CONFIRMED")
    expect_problem(
        get_order(bob, confirmed, "reference-cross-tenant"),
        404,
        "order.not-found",
        "cross-tenant lookup",
        correlation="reference-cross-tenant",
    )

    rejected, _ = create(alice, 200, 2, "reference-rejected")
    eventually_status(alice, rejected, "REJECTED")

    def competing(index: int) -> str:
        order_id, _ = create(alice, 300, 2, f"reference-concurrent-{index}")
        return eventually_status(
            alice,
            order_id,
            {"CONFIRMED", "REJECTED"},
        )["status"]

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        statuses = list(executor.map(competing, range(4)))
    if statuses.count("CONFIRMED") != 2 or statuses.count("REJECTED") != 2:
        raise AssertionError(f"bounded inventory violated: terminal counts={statuses}")

    alice_parallel, bob_parallel = None, None
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        a = executor.submit(create, alice, 100, 1, "reference-tenant-a-parallel")
        b = executor.submit(create, bob, 100, 1, "reference-tenant-b-parallel")
        alice_parallel = a.result()[0]
        bob_parallel = b.result()[0]
    eventually_status(alice, alice_parallel, "CONFIRMED")
    eventually_status(bob, bob_parallel, "CONFIRMED")
    expect_problem(
        get_order(alice, bob_parallel, "reference-a-cannot-see-b"),
        404,
        "order.not-found",
        "parallel tenant-b leak",
        correlation="reference-a-cannot-see-b",
    )
    expect_problem(
        get_order(bob, alice_parallel, "reference-b-cannot-see-a"),
        404,
        "order.not-found",
        "parallel tenant-a leak",
        correlation="reference-b-cannot-see-a",
    )
    print(
        "reference acceptance: PASS "
        f"(topology={TOPOLOGY}; auth, RFC9457, native responses, route isolation, "
        "tenant isolation, async fulfillment, bounded stock)"
    )


def create_pending(output: pathlib.Path) -> None:
    alice = login("alice", "alice-password", "tenant-a")
    order_id, response = create(alice, 100, 1, "reference-kafka-outage")
    if response.json().get("status") != "PENDING_STOCK":
        raise AssertionError("Kafka outage order did not commit as PENDING_STOCK")
    queried = expect(
        get_order(alice, order_id, "reference-outage-query"),
        200,
        "query during Kafka outage",
    )
    expect_native_json(queried, "query during Kafka outage")
    expect_correlation(queried, "reference-outage-query", "query during Kafka outage")
    output.write_text(order_id, encoding="utf-8")
    output.chmod(0o600)
    print("reference recovery phase 1: PASS (business row and outbox committed while Kafka unavailable)")


def resume(order_id: str) -> None:
    alice = login("alice", "alice-password", "tenant-a")
    eventually_status(alice, order_id, "CONFIRMED", timeout=60)
    print("reference recovery phase 2: PASS (restart reclaimed outbox and completed order)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: blackbox.py acceptance|create-pending|resume [gateway] [state]")
    command = sys.argv[1]
    if command == "acceptance":
        acceptance()
    elif command == "create-pending":
        create_pending(pathlib.Path(sys.argv[3]))
    elif command == "resume":
        resume(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8").strip())
    else:
        raise SystemExit("unknown command")
