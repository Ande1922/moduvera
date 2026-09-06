from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "probe.py"
SPEC = importlib.util.spec_from_file_location("probe", MODULE_PATH)
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROBE)


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


if __name__ == "__main__":
    unittest.main()
