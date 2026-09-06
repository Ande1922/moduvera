from __future__ import annotations

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "header_proxy.py"
SPEC = importlib.util.spec_from_file_location("header_proxy", MODULE_PATH)
HEADER_PROXY = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(HEADER_PROXY)


class RecordingBackendHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    requests: list[tuple[str, bytes]] = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self.requests.append((self.path, body))
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        return


class HeaderProxyTest(unittest.TestCase):
    def setUp(self) -> None:
        RecordingBackendHandler.requests = []
        self.directory = tempfile.TemporaryDirectory()
        self.backend = ThreadingHTTPServer(("127.0.0.1", 0), RecordingBackendHandler)
        recorder = HEADER_PROXY.Recorder("test-proxy", Path(self.directory.name) / "headers.jsonl")
        self.proxy = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            HEADER_PROXY.handler_type("127.0.0.1", self.backend.server_port, recorder),
        )
        self.threads = [
            threading.Thread(target=self.backend.serve_forever, daemon=True),
            threading.Thread(target=self.proxy.serve_forever, daemon=True),
        ]
        for thread in self.threads:
            thread.start()

    def tearDown(self) -> None:
        self.proxy.shutdown()
        self.backend.shutdown()
        self.proxy.server_close()
        self.backend.server_close()
        self.directory.cleanup()

    def test_forwards_chunked_json_without_changing_its_bytes(self) -> None:
        body = b'{"audience":"catalog-service","tenantId":"tenant-a"}'
        connection = http.client.HTTPConnection("127.0.0.1", self.proxy.server_port)
        connection.request(
            "POST",
            "/internal/api/v1/service-token",
            body=iter((body[:17], body[17:])),
            headers={"Content-Type": "application/json"},
            encode_chunked=True,
        )
        response = connection.getresponse()

        self.assertEqual(200, response.status)
        self.assertEqual(body, response.read())
        self.assertEqual([("/internal/api/v1/service-token", body)], RecordingBackendHandler.requests)
        connection.close()

    def test_forwards_fixed_length_body_and_records_correlation(self) -> None:
        body = b'{"fixed":true}'
        connection = http.client.HTTPConnection("127.0.0.1", self.proxy.server_port)
        connection.request(
            "POST",
            "/fixed",
            body=body,
            headers={"Content-Type": "application/json", "X-Correlation-Id": "corr-fixed"},
        )
        response = connection.getresponse()

        self.assertEqual(200, response.status)
        self.assertEqual(body, response.read())
        self.assertEqual([("/fixed", body)], RecordingBackendHandler.requests)
        recorded = json.loads((Path(self.directory.name) / "headers.jsonl").read_text())
        self.assertEqual("corr-fixed", recorded["correlationId"])
        connection.close()

    def test_rejects_oversized_and_ambiguous_framing_without_forwarding(self) -> None:
        oversized = (
            "POST /oversized HTTP/1.1\r\n"
            "Host: localhost\r\n"
            f"Content-Length: {HEADER_PROXY.MAX_REQUEST_BODY_BYTES + 1}\r\n"
            "Connection: close\r\n\r\n"
        ).encode()
        ambiguous = (
            "POST /ambiguous HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "Content-Length: 4\r\n"
            "Transfer-Encoding: chunked\r\n"
            "Connection: close\r\n\r\n"
            "4\r\ntest\r\n0\r\n\r\n"
        ).encode()

        self.assertEqual(400, self.send_raw(oversized))
        self.assertEqual(400, self.send_raw(ambiguous))
        self.assertEqual([], RecordingBackendHandler.requests)

    def send_raw(self, request: bytes) -> int:
        with socket.create_connection(("127.0.0.1", self.proxy.server_port)) as connection:
            connection.sendall(request)
            response = http.client.HTTPResponse(connection)
            response.begin()
            response.read()
            return response.status


if __name__ == "__main__":
    unittest.main()
