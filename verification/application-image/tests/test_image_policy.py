#!/usr/bin/env python3
"""Negative fixtures for the Runnable App image credential policy."""

from __future__ import annotations

from io import BytesIO
from pathlib import Path
import sys
import tarfile


IMAGE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(IMAGE_DIR))

from image_policy import ImagePolicyError, scan_tar_stream  # noqa: E402


def archive(name: str, content: bytes) -> BytesIO:
    stream = BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as output:
        member = tarfile.TarInfo(name)
        member.size = len(content)
        output.addfile(member, BytesIO(content))
    stream.seek(0)
    return stream


def expect_rejected(name: str, content: bytes, marker: str) -> None:
    try:
        scan_tar_stream(archive(name, content))
    except ImagePolicyError as error:
        if marker in str(error):
            raise AssertionError("policy error disclosed fixture content") from error
    else:
        raise AssertionError(f"policy accepted prohibited fixture kind: {name}")


scan_tar_stream(
    archive(
        "application.yml",
        b"password: ${DATABASE_PASSWORD}\nclient-secret: ${SERVICE_SECRET}\n",
    )
)
scan_tar_stream(
    archive(
        "application.properties",
        "password=${DATABASE_PASSWORD}\n".encode("utf-16"),
    )
)
scan_tar_stream(archive("application.jar", b"PK\x03\x04\x00\xff\x00binary"))
scan_tar_stream(archive("BOOT-INF/classes/Example.class", b"\xca\xfe\xba\xbe\x00\xff"))

fixture_marker = "synthetic-value-must-not-be-printed-4821"
credential_key = "client-" + "secret"
json_credential_key = "client_" + "secret"
password_key = "pass" + "word"
private_key_header = "-----BEGIN " + "PRIVATE KEY-----"
expect_rejected(".env", fixture_marker.encode(), fixture_marker)
expect_rejected("runtime-signing.p12", fixture_marker.encode(), fixture_marker)
expect_rejected(
    "runtime.pem",
    f"{private_key_header}\n{fixture_marker}\n".encode(),
    fixture_marker,
)
expect_rejected(
    "application.yml",
    f"{credential_key}: {fixture_marker}\n".encode(),
    fixture_marker,
)
expect_rejected(
    "application.yaml",
    f"{password_key}: |-\n  {fixture_marker}\nnext: value\n".encode(),
    fixture_marker,
)
expect_rejected(
    "runtime.json",
    f'{{"{json_credential_key}":"{fixture_marker}"}}\n'.encode(),
    fixture_marker,
)
expect_rejected(
    "application.yml",
    f"{password_key}: ${{DB_PASSWORD:{fixture_marker}}}\n".encode(),
    fixture_marker,
)
expect_rejected(
    "application.properties",
    f"{password_key}={fixture_marker}\n".encode("utf-16"),
    fixture_marker,
)
expect_rejected("application.txt", b"ordinary\x00text\n", fixture_marker)
expect_rejected("application.conf", b"ordinary=\xff\xfe\xfa\n", fixture_marker)

print("Application image sensitive-content fixtures: PASS")
