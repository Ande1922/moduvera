#!/usr/bin/env python3
"""Fail-closed policy checks for files packaged in Runnable App images."""

from __future__ import annotations

import codecs
from pathlib import PurePosixPath
import re
import tarfile
from typing import BinaryIO


MAX_TEXT_FILE_BYTES = 8 * 1024 * 1024
TEXT_SUFFIXES = {
    ".conf",
    ".config",
    ".csv",
    ".env",
    ".ini",
    ".json",
    ".md",
    ".properties",
    ".sh",
    ".sql",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
KEYSTORE_SUFFIXES = {".jks", ".key", ".keystore", ".p12", ".pfx"}
PRIVATE_KEY = re.compile(br"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
CREDENTIAL_ASSIGNMENT = re.compile(
    r"(?i)(?<![\w-])(?P<key_quote>['\"]?)(?:password|passwd|token|secret|"
    r"api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token)"
    r"(?P=key_quote)(?![\w-])\s*[:=]\s*"
    r"(?P<value>\$\{[^}\r\n]+\}|\"(?:\\.|[^\"\\])+\"|"
    r"'(?:\\.|[^'\\])+'|[^\s,;}\]]+)"
)
YAML_BLOCK_CREDENTIAL = re.compile(
    r"(?i)^(?P<indent> *)(?:- +)?(?:password|passwd|token|secret|api[_-]?key|"
    r"client[_-]?secret|access[_-]?token|refresh[_-]?token)\s*:\s*"
    r"[|>](?:[1-9][+-]?|[+-][1-9]?)?\s*(?:#.*)?$"
)
PLACEHOLDER = re.compile(
    r"^(?:\$\{[A-Z_][A-Z0-9_]*\}|<[^>\r\n]+>|\[?REDACTED\]?|"
    r"CHANGEME|example|dummy|test)$",
    re.IGNORECASE,
)


class ImagePolicyError(RuntimeError):
    """A packaged image violates the credential or artifact policy."""


def _is_prohibited_name(name: str) -> bool:
    path = PurePosixPath(name.removeprefix("./"))
    basename = path.name.lower()
    if basename == ".env" or basename.startswith(".env."):
        return True
    if basename in {"id_dsa", "id_ecdsa", "id_ed25519", "id_rsa"}:
        return True
    if path.suffix.lower() in KEYSTORE_SUFFIXES:
        return True
    stem = path.stem.lower()
    return stem in {"credential", "credentials", "secret", "secrets"} and (
        not path.suffix or path.suffix.lower() in TEXT_SUFFIXES
    )


def _is_sensitive_assignment(match: re.Match[str]) -> bool:
    raw = match.group("value")
    quoted = len(raw) >= 2 and raw[0] in {"'", '"'} and raw[-1] == raw[0]
    value = raw[1:-1] if quoted else raw
    if PLACEHOLDER.fullmatch(value):
        return False
    if quoted:
        return True
    if len(value) >= 12:
        return True
    return bool(
        len(value) >= 8
        and re.search(r"[A-Za-z]", value)
        and re.search(r"\d", value)
    )


def _decode_text(content: bytes, is_declared_text: bool) -> str | None:
    if content.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        try:
            text = content.decode("utf-16")
        except UnicodeDecodeError as error:
            if is_declared_text:
                raise ImagePolicyError("image contains an unscannable text artifact") from error
            return None
        if "\0" in text:
            if is_declared_text:
                raise ImagePolicyError("image contains an unscannable text artifact")
            return None
        return text
    if b"\0" in content:
        if is_declared_text:
            raise ImagePolicyError("image contains an unscannable text artifact")
        return None
    try:
        return content.decode("utf-8")
    except UnicodeDecodeError as error:
        if is_declared_text:
            raise ImagePolicyError("image contains an unscannable text artifact") from error
        return None


def _scan_content(member: tarfile.TarInfo, content: bytes) -> None:
    if PRIVATE_KEY.search(content):
        raise ImagePolicyError("image contains private key material")
    suffix = PurePosixPath(member.name).suffix.lower()
    is_declared_text = suffix in TEXT_SUFFIXES
    if len(content) > MAX_TEXT_FILE_BYTES:
        if is_declared_text:
            raise ImagePolicyError("image contains an oversized unscannable text artifact")
        return
    text = _decode_text(content, is_declared_text)
    if text is None:
        return
    if any(_is_sensitive_assignment(match) for match in CREDENTIAL_ASSIGNMENT.finditer(text)):
        raise ImagePolicyError("image contains a high-confidence credential assignment")
    if suffix in {".yaml", ".yml"}:
        lines = text.splitlines()
        for index, line in enumerate(lines):
            header = YAML_BLOCK_CREDENTIAL.match(line)
            if header is None:
                continue
            parent_indent = len(header.group("indent"))
            value: list[str] = []
            for candidate in lines[index + 1 :]:
                if candidate.strip() and len(candidate) - len(candidate.lstrip(" ")) <= parent_indent:
                    break
                if candidate.strip():
                    value.append(candidate.strip())
            if value and not PLACEHOLDER.fullmatch("\n".join(value)):
                raise ImagePolicyError("image contains a high-confidence credential assignment")


def scan_tar_stream(stream: BinaryIO) -> None:
    """Scan an untrusted tar stream without extracting it or echoing its content."""

    try:
        with tarfile.open(fileobj=stream, mode="r|*") as archive:
            for member in archive:
                if _is_prohibited_name(member.name):
                    raise ImagePolicyError("image contains a prohibited credential artifact")
                if member.issym() or member.islnk():
                    raise ImagePolicyError("image application content contains a link")
                if not member.isfile():
                    continue
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise ImagePolicyError("image content could not be inspected")
                _scan_content(member, extracted.read(MAX_TEXT_FILE_BYTES + 1))
    except (tarfile.TarError, OSError) as error:
        raise ImagePolicyError("image content could not be inspected") from error
