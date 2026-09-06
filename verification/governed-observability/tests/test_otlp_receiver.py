from __future__ import annotations

import importlib.util
from http.client import HTTPConnection
import json
from pathlib import Path
import tempfile
import threading
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "otlp_receiver.py"
SPEC = importlib.util.spec_from_file_location("otlp_receiver", MODULE_PATH)
OTLP = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(OTLP)


def varint(value: int) -> bytes:
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def length_field(number: int, value: bytes) -> bytes:
    return varint((number << 3) | 2) + varint(len(value)) + value


def enum_field(number: int, value: int) -> bytes:
    return varint(number << 3) + varint(value)


def string_value(value: str) -> bytes:
    return length_field(1, value.encode())


def key_value(key: str, value: str) -> bytes:
    return length_field(1, key.encode()) + length_field(2, string_value(value))


def export_request(attribute_value: str = "/orders") -> bytes:
    resource = length_field(1, key_value("service.name", "order"))
    scope = length_field(1, b"io.opentelemetry.kafka-clients-0.11") + length_field(2, b"2.31.1-alpha")
    link = length_field(1, b"\x03" * 16) + length_field(2, b"\x04" * 8)
    span = b"".join(
        (
            length_field(1, b"\x01" * 16),
            length_field(2, b"\x02" * 8),
            length_field(4, b"\x05" * 8),
            length_field(5, b"orders send"),
            enum_field(6, 4),
            length_field(9, key_value("url.path", attribute_value)),
            length_field(13, link),
        )
    )
    scope_spans = length_field(1, scope) + length_field(2, span)
    resource_spans = length_field(1, resource) + length_field(2, scope_spans)
    return length_field(1, resource_spans)


class OtlpReceiverTest(unittest.TestCase):
    def test_decodes_resource_scope_span_and_link(self) -> None:
        spans = OTLP.parse_export_request(export_request())

        self.assertEqual(1, len(spans))
        self.assertEqual("01" * 16, spans[0]["traceId"])
        self.assertEqual("02" * 8, spans[0]["spanId"])
        self.assertEqual("05" * 8, spans[0]["parentSpanId"])
        self.assertEqual("PRODUCER", spans[0]["kind"])
        self.assertEqual("order", spans[0]["resource"]["service.name"])
        self.assertEqual("io.opentelemetry.kafka-clients-0.11", spans[0]["scope"]["name"])
        self.assertEqual("03" * 16, spans[0]["links"][0]["traceId"])

    def test_scans_raw_payload_and_redacts_normalized_output(self) -> None:
        sentinel = "verification-secret-sentinel"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = OTLP.ReceiverState(
                root / "spans.jsonl",
                root / "status.json",
                {"credential": sentinel},
            )

            state.accept(export_request(sentinel), "application/x-protobuf", "")

            status = json.loads((root / "status.json").read_text())
            span = json.loads((root / "spans.jsonl").read_text())
            self.assertEqual(["credential"], status["sensitiveMatches"])
            self.assertEqual("<redacted:credential>", span["attributes"]["url.path"])
            self.assertNotIn(sentinel, (root / "spans.jsonl").read_text())

    def test_counts_only_canonical_signal_categories_for_rejected_posts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = OTLP.ReceiverState(root / "spans.jsonl", root / "status.json", {"x": "y"})
            server = OTLP.ThreadingHTTPServer(("127.0.0.1", 0), OTLP.handler_type(state))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                connection = HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                for target in ("/v1/metrics?credential=secret", "/v1/logs", "/unknown/raw-target"):
                    connection.request("POST", target, body=b"payload")
                    response = connection.getresponse()
                    self.assertEqual(404, response.status)
                    response.read()
                connection.close()
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

            status_text = (root / "status.json").read_text()
            status = json.loads(status_text)
            self.assertEqual(
                {"logs": 1, "metrics": 1, "other": 1, "traces": 0},
                status["postRequestsBySignal"],
            )
            self.assertNotIn("credential", status_text)
            self.assertNotIn("raw-target", status_text)


if __name__ == "__main__":
    unittest.main()
