#!/usr/bin/env python3
"""Bounded verification-only OTLP/HTTP trace receiver with no external packages."""

from __future__ import annotations

import argparse
import gzip
import json
import os
from pathlib import Path
import signal
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Iterator


KIND_NAMES = {
    0: "UNSPECIFIED",
    1: "INTERNAL",
    2: "SERVER",
    3: "CLIENT",
    4: "PRODUCER",
    5: "CONSUMER",
}


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data) and shift < 70:
        current = data[offset]
        offset += 1
        value |= (current & 0x7F) << shift
        if current < 0x80:
            return value, offset
        shift += 7
    raise ValueError("invalid protobuf varint")


def protobuf_fields(data: bytes) -> Iterator[tuple[int, int, Any]]:
    offset = 0
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if field_number == 0:
            raise ValueError("invalid protobuf field number")
        if wire_type == 0:
            value, offset = read_varint(data, offset)
        elif wire_type == 1:
            if offset + 8 > len(data):
                raise ValueError("truncated fixed64 field")
            value = data[offset : offset + 8]
            offset += 8
        elif wire_type == 2:
            length, offset = read_varint(data, offset)
            if length < 0 or offset + length > len(data):
                raise ValueError("truncated length-delimited field")
            value = data[offset : offset + length]
            offset += length
        elif wire_type == 5:
            if offset + 4 > len(data):
                raise ValueError("truncated fixed32 field")
            value = data[offset : offset + 4]
            offset += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
        yield field_number, wire_type, value


def utf8(value: bytes) -> str:
    return value.decode("utf-8", errors="replace")


def first_field(data: bytes, expected: int) -> tuple[int, Any] | None:
    return next(((wire, value) for field, wire, value in protobuf_fields(data) if field == expected), None)


def any_value(data: bytes) -> Any:
    item = next(protobuf_fields(data), None)
    if item is None:
        return None
    field, wire, value = item
    if field == 1 and wire == 2:
        return utf8(value)
    if field == 2 and wire == 0:
        return bool(value)
    if field == 3 and wire == 0:
        return value
    if field == 4 and wire == 1:
        return struct.unpack("<d", value)[0]
    if field == 5 and wire == 2:
        return [any_value(item_value) for item_field, item_wire, item_value in protobuf_fields(value) if item_field == 1 and item_wire == 2]
    if field == 6 and wire == 2:
        return key_values(value)
    if field == 7 and wire == 2:
        return value.hex()
    return None


def key_value(data: bytes) -> tuple[str, Any]:
    key = ""
    value: Any = None
    for field, wire, item in protobuf_fields(data):
        if field == 1 and wire == 2:
            key = utf8(item)
        elif field == 2 and wire == 2:
            value = any_value(item)
    return key, value


def key_values(data: bytes, field_number: int = 1) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for field, wire, item in protobuf_fields(data):
        if field == field_number and wire == 2:
            key, value = key_value(item)
            if key:
                values[key] = value
    return values


def parse_scope(data: bytes) -> dict[str, Any]:
    scope: dict[str, Any] = {}
    for field, wire, value in protobuf_fields(data):
        if field == 1 and wire == 2:
            scope["name"] = utf8(value)
        elif field == 2 and wire == 2:
            scope["version"] = utf8(value)
    return scope


def parse_link(data: bytes) -> dict[str, Any]:
    link: dict[str, Any] = {}
    for field, wire, value in protobuf_fields(data):
        if field == 1 and wire == 2:
            link["traceId"] = value.hex()
        elif field == 2 and wire == 2:
            link["spanId"] = value.hex()
        elif field == 3 and wire == 2:
            link["traceState"] = utf8(value)
        elif field == 4 and wire == 2:
            link["attributes"] = key_values(data, 4)
    return link


def parse_status(data: bytes) -> dict[str, Any]:
    status: dict[str, Any] = {}
    for field, wire, value in protobuf_fields(data):
        if field == 2 and wire == 2:
            status["message"] = utf8(value)
        elif field == 3 and wire == 0:
            status["code"] = value
    return status


def parse_span(data: bytes, resource: dict[str, Any], scope: dict[str, Any]) -> dict[str, Any]:
    span: dict[str, Any] = {"resource": resource, "scope": scope, "attributes": {}, "links": []}
    for field, wire, value in protobuf_fields(data):
        if field == 1 and wire == 2:
            span["traceId"] = value.hex()
        elif field == 2 and wire == 2:
            span["spanId"] = value.hex()
        elif field == 3 and wire == 2:
            span["traceState"] = utf8(value)
        elif field == 4 and wire == 2:
            span["parentSpanId"] = value.hex()
        elif field == 5 and wire == 2:
            span["name"] = utf8(value)
        elif field == 6 and wire == 0:
            span["kind"] = KIND_NAMES.get(value, str(value))
        elif field == 7 and wire == 1:
            span["startUnixNano"] = int.from_bytes(value, "little")
        elif field == 8 and wire == 1:
            span["endUnixNano"] = int.from_bytes(value, "little")
        elif field == 9 and wire == 2:
            key, item = key_value(value)
            if key:
                span["attributes"][key] = item
        elif field == 13 and wire == 2:
            span["links"].append(parse_link(value))
        elif field == 15 and wire == 2:
            span["status"] = parse_status(value)
        elif field == 16 and wire == 1:
            span["flags"] = int.from_bytes(value, "little")
    return span


def parse_export_request(data: bytes) -> list[dict[str, Any]]:
    spans: list[dict[str, Any]] = []
    for field, wire, resource_spans in protobuf_fields(data):
        if field != 1 or wire != 2:
            continue
        resource: dict[str, Any] = {}
        scope_messages: list[bytes] = []
        for resource_field, resource_wire, resource_value in protobuf_fields(resource_spans):
            if resource_field == 1 and resource_wire == 2:
                resource = key_values(resource_value)
            elif resource_field == 2 and resource_wire == 2:
                scope_messages.append(resource_value)
        for scope_message in scope_messages:
            scope: dict[str, Any] = {}
            span_messages: list[bytes] = []
            for scope_field, scope_wire, scope_value in protobuf_fields(scope_message):
                if scope_field == 1 and scope_wire == 2:
                    scope = parse_scope(scope_value)
                elif scope_field == 2 and scope_wire == 2:
                    span_messages.append(scope_value)
            spans.extend(parse_span(span_message, resource, scope) for span_message in span_messages)
    return spans


class ReceiverState:
    def __init__(self, output: Path, status: Path, sentinels: dict[str, str]):
        self.output = output
        self.status = status
        self.sentinels = sentinels
        self.lock = threading.Lock()
        self.requests = 0
        self.payload_bytes = 0
        self.spans = 0
        self.content_types: set[str] = set()
        self.content_encodings: set[str] = set()
        self.sensitive_matches: set[str] = set()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text("", encoding="utf-8")
        self.write_status()

    def sanitize(self, value: Any) -> Any:
        if isinstance(value, str):
            sanitized = value
            for label, sentinel in self.sentinels.items():
                if sentinel and sentinel in sanitized:
                    sanitized = sanitized.replace(sentinel, f"<redacted:{label}>")
            return sanitized
        if isinstance(value, list):
            return [self.sanitize(item) for item in value]
        if isinstance(value, dict):
            return {key: self.sanitize(item) for key, item in value.items()}
        return value

    def accept(self, payload: bytes, content_type: str, content_encoding: str) -> None:
        decoded = gzip.decompress(payload) if content_encoding == "gzip" else payload
        parsed = parse_export_request(decoded)
        with self.lock:
            self.requests += 1
            self.payload_bytes += len(decoded)
            self.spans += len(parsed)
            self.content_types.add(content_type)
            self.content_encodings.add(content_encoding or "identity")
            for label, sentinel in self.sentinels.items():
                if sentinel and sentinel.encode("utf-8") in decoded:
                    self.sensitive_matches.add(label)
            with self.output.open("a", encoding="utf-8") as stream:
                for span in parsed:
                    record = self.sanitize(span)
                    record["receivedUnixNano"] = time.time_ns()
                    stream.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
            self.write_status()

    def write_status(self) -> None:
        value = {
            "requests": self.requests,
            "payloadBytesScanned": self.payload_bytes,
            "spans": self.spans,
            "contentTypes": sorted(self.content_types),
            "contentEncodings": sorted(self.content_encodings),
            "sentinelLabelsScanned": sorted(self.sentinels),
            "sensitiveMatches": sorted(self.sensitive_matches),
        }
        temporary = self.status.with_suffix(self.status.suffix + ".partial")
        temporary.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temporary, self.status)


def handler_type(state: ReceiverState):
    class Handler(BaseHTTPRequestHandler):
        server_version = "moduvera-verification-otlp"

        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_error(404)
                return
            body = b'{"status":"UP"}\n'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self) -> None:
            if self.path != "/v1/traces":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 16 * 1024 * 1024:
                self.send_error(400)
                return
            try:
                state.accept(
                    self.rfile.read(length),
                    self.headers.get("Content-Type", ""),
                    self.headers.get("Content-Encoding", ""),
                )
            except (EOFError, OSError, ValueError) as error:
                self.send_error(400, type(error).__name__)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format_string: str, *args: Any) -> None:
            return

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--sentinels", type=Path, required=True)
    args = parser.parse_args()

    sentinels = json.loads(args.sentinels.read_text(encoding="utf-8"))
    if not isinstance(sentinels, dict) or not all(
        isinstance(label, str) and isinstance(value, str) and value for label, value in sentinels.items()
    ):
        raise SystemExit("sentinels must be a non-empty string-to-string JSON object")
    state = ReceiverState(args.output, args.status, sentinels)
    server = ThreadingHTTPServer((args.host, args.port), handler_type(state))
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
