from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import threading
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "probe.py"
SPEC = importlib.util.spec_from_file_location("probe", MODULE_PATH)
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROBE)


class LoginHandler(BaseHTTPRequestHandler):
    payload: dict[str, str] = {}
    opaque_session = ""

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        self.__class__.payload = json.loads(self.rfile.read(length))
        response_body = {}
        response_body["token"] = self.opaque_session
        response = json.dumps(response_body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, format_string: str, *args: object) -> None:
        return


def span(
    span_id: str,
    kind: str,
    service: str,
    attributes: dict[str, str],
    *,
    parent_span_id: str | None = None,
    scope: str = "io.opentelemetry.test",
    links: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    result: dict[str, object] = {
        "traceId": PROBE.VALID_TRACE_ID,
        "spanId": span_id,
        "kind": kind,
        "resource": {"service.name": service},
        "scope": {"name": scope},
        "attributes": attributes,
        "links": links or [],
    }
    if parent_span_id is not None:
        result["parentSpanId"] = parent_span_id
    return result


class ValidUpstreamHttpSpansTest(unittest.TestCase):
    def valid_spans(self) -> list[dict[str, object]]:
        gateway_server = span(
            "gateway-server",
            "SERVER",
            "gateway",
            {"http.request.method": "POST", "url.path": "/api/identity/v1/session/login"},
            parent_span_id=PROBE.VALID_PARENT_ID,
        )
        gateway_client = span(
            "gateway-client",
            "CLIENT",
            "gateway",
            {"http.request.method": "POST", "url.full": "http://identity/v1/session/login"},
            parent_span_id="gateway-server",
        )
        identity_server = span(
            "identity-server",
            "SERVER",
            "identity",
            {"http.request.method": "POST", "http.route": "/v1/session/login"},
            parent_span_id="gateway-client",
        )
        return [gateway_server, gateway_client, identity_server]

    def test_accepts_direct_parent_chain(self) -> None:
        selected = PROBE.select_valid_upstream_http_spans(self.valid_spans())

        self.assertEqual(
            ["gateway-server", "gateway-client", "identity-server"],
            [candidate["spanId"] for candidate in selected],
        )

    def test_accepts_links_for_both_causal_edges(self) -> None:
        spans = self.valid_spans()
        spans[1].pop("parentSpanId")
        spans[1]["links"] = [{"traceId": PROBE.VALID_TRACE_ID, "spanId": "gateway-server"}]
        spans[2].pop("parentSpanId")
        spans[2]["links"] = [{"traceId": PROBE.VALID_TRACE_ID, "spanId": "gateway-client"}]

        selected = PROBE.select_valid_upstream_http_spans(spans)

        self.assertEqual(3, len(selected))

    def test_rejects_duplicate_endpoint_span(self) -> None:
        spans = self.valid_spans()
        duplicate = {**spans[1], "spanId": "duplicate-client"}

        with self.assertRaisesRegex(AssertionError, "expected exactly one span, got 2"):
            PROBE.select_valid_upstream_http_spans([*spans, duplicate])

    def test_rejects_non_agent_owner(self) -> None:
        spans = self.valid_spans()
        spans[1]["scope"] = {"name": "application.manual"}

        with self.assertRaisesRegex(AssertionError, "without an Agent instrumentation owner"):
            PROBE.select_valid_upstream_http_spans(spans)

    def test_rejects_disconnected_span(self) -> None:
        spans = self.valid_spans()
        spans[2]["parentSpanId"] = "unrelated"

        with self.assertRaisesRegex(AssertionError, "not causally connected"):
            PROBE.select_valid_upstream_http_spans(spans)


class LoginFixtureTest(unittest.TestCase):
    def test_sends_explicit_environment_values_in_the_login_payload(self) -> None:
        username = "fixture-user-" + secrets.token_hex(8)
        credential = secrets.token_urlsafe(24)
        tenant = "fixture-tenant-" + secrets.token_hex(8)
        opaque_session = secrets.token_urlsafe(32)
        environment = {
            PROBE.LOGIN_USERNAME_ENV: username,
            PROBE.LOGIN_CREDENTIAL_ENV: credential,
            PROBE.LOGIN_TENANT_ENV: tenant,
        }
        LoginHandler.payload = {}
        LoginHandler.opaque_session = opaque_session
        server = ThreadingHTTPServer(("127.0.0.1", 0), LoginHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        try:
            with mock.patch.dict(PROBE.os.environ, environment, clear=True):
                self.assertEqual(
                    opaque_session,
                    PROBE.login(f"http://127.0.0.1:{server.server_port}"),
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(username, LoginHandler.payload["username"])
        self.assertEqual(credential, LoginHandler.payload["password"])
        self.assertEqual(tenant, LoginHandler.payload["tenantId"])

    def test_rejects_each_missing_environment_value_before_request(self) -> None:
        environment = {
            PROBE.LOGIN_USERNAME_ENV: "fixture-user-" + secrets.token_hex(8),
            PROBE.LOGIN_CREDENTIAL_ENV: secrets.token_urlsafe(24),
            PROBE.LOGIN_TENANT_ENV: "fixture-tenant-" + secrets.token_hex(8),
        }
        for missing in environment:
            with self.subTest(missing=missing):
                incomplete = environment | {missing: ""}
                with mock.patch.dict(PROBE.os.environ, incomplete, clear=True):
                    with mock.patch.object(PROBE, "request") as request:
                        with self.assertRaisesRegex(RuntimeError, missing):
                            PROBE.login("http://127.0.0.1")
                request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
