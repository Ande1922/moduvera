#!/usr/bin/env python3
"""Strict launch-policy validation for the governed Java Agent runtime."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shlex
import sys
from urllib.parse import urlsplit


HANDSHAKE_PROPERTY_OPTION = "-Dio.github.ande1922.moduvera.otel.extension.active"


def fail(message: str) -> None:
    raise ValueError(message)


def validate_endpoint(value: str) -> None:
    if not value or any(character.isspace() or ord(character) < 32 for character in value):
        fail("Trace endpoint must not contain whitespace or control characters")
    try:
        endpoint = urlsplit(value)
        port = endpoint.port
    except ValueError as error:
        fail(f"Trace endpoint is invalid: {error}")
    if endpoint.scheme not in {"http", "https"}:
        fail("Trace endpoint scheme must be http or https")
    if not endpoint.hostname:
        fail("Trace endpoint must include a host")
    if endpoint.username is not None or endpoint.password is not None or "@" in endpoint.netloc:
        fail("Trace endpoint must not contain userinfo credentials")
    if endpoint.path != "/v1/traces":
        fail("Trace endpoint path must be exactly /v1/traces")
    if endpoint.query or endpoint.fragment:
        fail("Trace endpoint must not contain a query or fragment")
    if port is not None and not 1 <= port <= 65535:
        fail("Trace endpoint port is outside 1..65535")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_option_environments(values: list[str], jacoco: Path | None, jacoco_digest: str) -> None:
    approved_jacoco = jacoco.resolve() if jacoco else None
    if approved_jacoco is not None:
        if not approved_jacoco.is_file() or sha256(approved_jacoco) != jacoco_digest:
            fail("Approved JaCoCo Agent is missing or has the wrong SHA-256")
    for item in values:
        name, separator, raw = item.partition("=")
        if not separator:
            fail("Internal option-environment encoding is invalid")
        try:
            tokens = shlex.split(raw, posix=True)
        except ValueError as error:
            fail(f"{name} cannot be parsed safely: {error}")
        for token in tokens:
            if token == HANDSHAKE_PROPERTY_OPTION or token.startswith(HANDSHAKE_PROPERTY_OPTION + "="):
                fail(f"{name} must not predefine the governed extension handshake marker")
            if token.startswith("@"):
                fail(f"{name} must not use JVM argument files")
            if not token.startswith("-javaagent:"):
                continue
            agent_value = token.removeprefix("-javaagent:").split("=", 1)[0]
            if not agent_value:
                fail(f"{name} contains an empty Java Agent path")
            candidate = Path(agent_value).expanduser().resolve()
            if approved_jacoco is None or candidate != approved_jacoco:
                fail(f"{name} contains an unapproved pre-existing Java Agent: {agent_value}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--jacoco-agent", type=Path)
    parser.add_argument("--jacoco-sha256", default="")
    parser.add_argument("--option-env", action="append", default=[])
    args = parser.parse_args()
    try:
        validate_endpoint(args.endpoint)
        validate_option_environments(args.option_env, args.jacoco_agent, args.jacoco_sha256)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(78) from error


if __name__ == "__main__":
    main()
