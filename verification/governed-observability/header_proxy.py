#!/usr/bin/env python3
"""Verification-only reverse proxy that records W3C propagation headers."""

from __future__ import annotations

import argparse
import http.client
import json
from pathlib import Path
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit


HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
}


class Recorder:
    def __init__(self, name: str, output: Path):
        self.name = name
        self.output = output
        self.lock = threading.Lock()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text("", encoding="utf-8")

    def record(self, method: str, path: str, traceparent: str | None, tracestate: str | None) -> None:
        target = urlsplit(path)
        value = {
            "proxy": self.name,
            "method": method,
            "path": target.path,
            "queryPresent": bool(target.query),
            "traceparent": traceparent,
            "tracestate": tracestate,
            "observedUnixNano": time.time_ns(),
        }
        with self.lock, self.output.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")


def handler_type(target_host: str, target_port: int, recorder: Recorder):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def proxy(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else None
            headers = {
                key: value
                for key, value in self.headers.items()
                if key.lower() not in HOP_BY_HOP and key.lower() != "host"
            }
            recorder.record(
                self.command,
                self.path,
                self.headers.get("traceparent"),
                self.headers.get("tracestate"),
            )
            connection = http.client.HTTPConnection(target_host, target_port, timeout=15)
            try:
                connection.request(self.command, self.path, body=body, headers=headers)
                response = connection.getresponse()
                response_body = response.read()
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in HOP_BY_HOP and key.lower() != "content-length":
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(response_body)))
                self.end_headers()
                self.wfile.write(response_body)
            finally:
                connection.close()

        do_DELETE = proxy
        do_GET = proxy
        do_HEAD = proxy
        do_OPTIONS = proxy
        do_PATCH = proxy
        do_POST = proxy
        do_PUT = proxy

        def log_message(self, format_string: str, *args: Any) -> None:
            return

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--port-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    target = urlsplit(args.target)
    if target.scheme != "http" or not target.hostname or not target.port or target.path not in ("", "/"):
        raise SystemExit("target must be an explicit http://host:port origin")

    recorder = Recorder(args.name, args.output)
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        handler_type(target.hostname, target.port, recorder),
    )
    server.daemon_threads = True
    args.port_file.write_text(str(server.server_port) + "\n", encoding="utf-8")

    def stop(_signal_number: int, _frame: Any) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    server.serve_forever(poll_interval=0.1)
    server.server_close()


if __name__ == "__main__":
    main()
