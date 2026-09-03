#!/usr/bin/env python3
"""Repository-owned quality gate orchestration.

The public entry point is quality-gate.sh.  This module also contains small,
deterministic check implementations so the gate can test its own policy.
"""

from __future__ import annotations

import argparse
import codecs
from collections import deque
import contextlib
import ctypes
import datetime as dt
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
from typing import NamedTuple
import urllib.parse


MAX_COMPLETED_RUNS = 20
MAX_FAILURE_LINES = 20
MAX_TAIL_BYTES = 64 * 1024
MAX_REDACTED_LINE_CHARS = 4096
MAX_STREAM_BUFFER_CHARS = 2 * MAX_REDACTED_LINE_CHARS
STREAM_PATTERN_OVERLAP = 256
MAX_OPEN_CREDENTIAL_CHARS = 256 * 1024
MAX_SUMMARY_BYTES = 8192
PROCESS_GROUP_TERM_SECONDS = 2.0
PROCESS_GROUP_KILL_SECONDS = 5.0
DOC_SUFFIXES = {".md"}
PRIVATE_KEY_BEGIN = re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
PRIVATE_KEY_END = re.compile(r"-----END [A-Z0-9 ]*PRIVATE KEY-----")
CREDENTIAL_KEY = (
    r"(?:password|passwd|token|secret|api[_-]?key|client[_-]?secret|"
    r"access[_-]?token|refresh[_-]?token)"
)
CREDENTIAL_ASSIGNMENT = re.compile(
    rf"(?i)(?P<prefix>(?P<key_quote>['\"]?)\b{CREDENTIAL_KEY}\b"
    rf"(?P=key_quote)\s*[:=]\s*)"
    r"(?P<value>\$\{[^}\r\n]+\}|\"(?:\\.|[^\"\\])+\"|"
    r"'(?:\\.|[^'\\])+'|[^\s,;}\]]+)"
)
CREDENTIAL_QUOTED_START = re.compile(
    rf"(?i)(?P<prefix>(?P<key_quote>['\"]?)\b{CREDENTIAL_KEY}\b"
    rf"(?P=key_quote)\s*[:=]\s*)(?P<value_quote>['\"])"
)
YAML_BLOCK_CREDENTIAL_HEADER = re.compile(
    rf"(?i)^(?P<indent> *)(?P<sequence>- +)?"
    rf"(?P<prefix>['\"]?{CREDENTIAL_KEY}['\"]?\s*:\s*)"
    r"(?P<style>[|>])(?P<indicators>(?:[1-9][+-]?|[+-][1-9]?))?"
    r"\s*(?:#.*)?(?:\r?\n)?$"
)
STANDALONE_SECRET_PATTERNS = (
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}"),
)


def _redact_credential(match: re.Match[str]) -> str:
    value = match.group("value")
    if len(value) >= 2 and value[0] in {'"', "'"} and value[-1] == value[0]:
        replacement = f"{value[0]}[REDACTED]{value[0]}"
    else:
        replacement = "[REDACTED]"
    return match.group("prefix") + replacement


EXACT_PLACEHOLDER = re.compile(
    r"^(?:\$\{[A-Za-z_][A-Za-z0-9_]*\}|<[A-Za-z_][A-Za-z0-9_-]*>|"
    r"\[?REDACTED\]?|CHANGEME|example|dummy|test)$",
    re.IGNORECASE,
)
JAVA_CODE_EXPRESSION = re.compile(
    r"^(?:null|true|false|Optional\.empty\(\)|"
    r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*|"
    r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\([^\r\n]*\))$"
)


def _credential_value(match: re.Match[str]) -> tuple[str, bool]:
    raw = match.group("value")
    quoted = len(raw) >= 2 and raw[0] in {'"', "'"} and raw[-1] == raw[0]
    if quoted:
        return raw[1:-1], True
    return raw, False


def is_sensitive_credential_assignment(
    match: re.Match[str], path: str | None = None
) -> bool:
    value, quoted = _credential_value(match)
    if EXACT_PLACEHOLDER.fullmatch(value):
        return False
    if (
        path is not None
        and path.lower().endswith(".java")
        and JAVA_CODE_EXPRESSION.fullmatch(value)
    ):
        return False
    if quoted:
        return True
    if value.startswith("${") and not EXACT_PLACEHOLDER.fullmatch(value):
        return True
    if len(value) >= 12:
        return True
    return bool(re.search(r"[A-Za-z]", value) and re.search(r"\d", value) and len(value) >= 8)


REDACTIONS = (
    (re.compile(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+"), r"\1[REDACTED]"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "[REDACTED_AWS_KEY]"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"), "[REDACTED_GITHUB_TOKEN]"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"), "[REDACTED_SLACK_TOKEN]"),
)


class GateError(RuntimeError):
    """A deterministic, user-actionable gate failure."""


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def resolve_commit(repo: Path, revision: str, label: str) -> str:
    if not revision:
        raise GateError(f"missing required {label}; pass --{label} <revision>")
    result = git(repo, "rev-parse", "--verify", f"{revision}^{{commit}}", check=False)
    if result.returncode != 0:
        raise GateError(f"cannot resolve {label} revision: {revision}")
    return result.stdout.strip()


def changed_entries(repo: Path, base: str, head: str) -> list[tuple[str, tuple[str, ...]]]:
    result = subprocess.run(
        [
            "git",
            "diff",
            "--name-status",
            "-z",
            "--find-renames",
            "--find-copies-harder",
            base,
            head,
        ],
        cwd=repo,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise GateError(result.stderr.decode("utf-8", "replace").strip() or "git diff failed")
    fields = result.stdout.split(b"\0")
    if fields and fields[-1] == b"":
        fields.pop()
    entries: list[tuple[str, tuple[str, ...]]] = []
    index = 0
    while index < len(fields):
        status_text = fields[index].decode("utf-8", "surrogateescape")
        index += 1
        path_count = 2 if status_text[:1] in {"R", "C"} else 1
        if index + path_count > len(fields):
            raise GateError("unable to parse changed paths")
        paths = tuple(
            field.decode("utf-8", "surrogateescape")
            for field in fields[index : index + path_count]
        )
        index += path_count
        entries.append((status_text, paths))
    return entries


def tree_mode(repo: Path, revision: str, path: str) -> str | None:
    result = git(repo, "ls-tree", revision, "--", path, check=False)
    if result.returncode != 0 or not result.stdout.strip():
        return None
    return result.stdout.split(None, 1)[0]


def classify(repo: Path, base: str, head: str) -> tuple[str, str, list[tuple[str, tuple[str, ...]]]]:
    entries = changed_entries(repo, base, head)
    if not entries:
        return "normal", "no changed paths; unable to prove docs-only", entries
    for status, paths in entries:
        kind = status[:1]
        display = " -> ".join(paths)
        if kind not in {"A", "M"}:
            return "normal", f"{status} change is not safely docs-only: {display}", entries
        path = paths[-1]
        if PurePosixPath(path).suffix.lower() not in DOC_SUFFIXES:
            return "normal", f"non-Markdown path changed: {path}", entries
        if tree_mode(repo, head, path) != "100644":
            return "normal", f"non-regular or executable Markdown path changed: {path}", entries
    return "docs-only", f"all {len(entries)} changed paths are regular Markdown files", entries


def changed_markdown_paths(entries: list[tuple[str, tuple[str, ...]]]) -> list[str]:
    return [
        paths[-1]
        for status, paths in entries
        if status[:1] in {"A", "M", "R", "C"}
        and PurePosixPath(paths[-1]).suffix.lower() in DOC_SUFFIXES
    ]


def blob(repo: Path, revision: str, path: str) -> str:
    result = git(repo, "show", f"{revision}:{path}", check=False)
    if result.returncode != 0:
        raise GateError(f"cannot read {path} at {revision}")
    return result.stdout


INLINE_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
REFERENCE_LINK = re.compile(r"^\s*\[[^\]]+\]:\s*(\S+)", re.MULTILINE)


def link_target(raw: str) -> str | None:
    value = raw.strip()
    if value.startswith("<") and ">" in value:
        value = value[1 : value.index(">")]
    elif " " in value:
        value = value.split(" ", 1)[0]
    value = urllib.parse.unquote(value).split("#", 1)[0].split("?", 1)[0]
    if not value or value.startswith(("http://", "https://", "mailto:", "tel:", "data:")):
        return None
    return value


def check_markdown_links(repo: Path, head: str, paths: list[str]) -> None:
    failures: list[str] = []
    for path in paths:
        content = blob(repo, head, path)
        raw_targets = INLINE_LINK.findall(content) + REFERENCE_LINK.findall(content)
        for raw in raw_targets:
            target = link_target(raw)
            if target is None:
                continue
            source_parent = PurePosixPath(path).parent
            candidate = PurePosixPath(target.lstrip("/")) if target.startswith("/") else source_parent / target
            normalized = os.path.normpath(candidate.as_posix())
            if normalized == ".." or normalized.startswith("../"):
                failures.append(f"{path}: local link escapes repository: {raw}")
                continue
            if git(repo, "cat-file", "-e", f"{head}:{normalized}", check=False).returncode != 0:
                failures.append(f"{path}: missing local link target: {raw}")
    if failures:
        raise GateError("\n".join(failures))
    print(f"checked local links in {len(paths)} changed Markdown file(s)")


def check_skill_structure(repo: Path, head: str) -> None:
    listing = git(repo, "ls-tree", "-r", "--name-only", head, "--", ".agents/skills", check=False)
    paths = [line for line in listing.stdout.splitlines() if line]
    roots = sorted({PurePosixPath(path).parts[2] for path in paths if len(PurePosixPath(path).parts) >= 3})
    failures: list[str] = []
    for name in roots:
        skill_path = f".agents/skills/{name}/SKILL.md"
        if skill_path not in paths or tree_mode(repo, head, skill_path) != "100644":
            failures.append(f"{skill_path}: required regular file is missing")
            continue
        content = blob(repo, head, skill_path)
        lines = content.splitlines()
        if not lines or lines[0] != "---" or "---" not in lines[1:]:
            failures.append(f"{skill_path}: YAML frontmatter is required")
            continue
        end = lines[1:].index("---") + 1
        frontmatter = lines[1:end]
        if not any(re.match(r"^name:\s*\S", line) for line in frontmatter):
            failures.append(f"{skill_path}: frontmatter name is required")
        if not any(re.match(r"^description:\s*\S", line) for line in frontmatter):
            failures.append(f"{skill_path}: frontmatter description is required")
    if failures:
        raise GateError("\n".join(failures))
    print(f"checked {len(roots)} project Skill directorie(s)")


class ChangedFileContext(NamedTuple):
    path: str
    content: str
    added_lines: frozenset[int]


HUNK_HEADER = re.compile(
    r"^@@ -\d+(?:,\d+)? \+(?P<start>\d+)(?:,(?P<count>\d+))? @@"
)


def _added_line_numbers(repo: Path, base: str, head: str, path: str) -> frozenset[int]:
    result = git(
        repo,
        "diff",
        "--unified=0",
        "--no-color",
        "--no-ext-diff",
        base,
        head,
        "--",
        path,
        check=False,
    )
    if result.returncode != 0:
        raise GateError(result.stderr.strip() or "cannot inspect changed content")
    added: set[int] = set()
    new_line: int | None = None
    for line in result.stdout.splitlines():
        header = HUNK_HEADER.match(line)
        if header:
            new_line = int(header.group("start"))
        elif new_line is None or line.startswith(("diff ", "index ", "---", "+++")):
            continue
        elif line.startswith("+") and not line.startswith("+++"):
            added.add(new_line)
            new_line += 1
        elif line.startswith("-") and not line.startswith("---"):
            continue
        elif not line.startswith("\\"):
            new_line += 1
    return frozenset(added)


def _blob_text(repo: Path, revision: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise GateError(f"cannot read {path} at {revision}")
    return result.stdout.decode("utf-8", "replace")


def changed_file_contexts(repo: Path, base: str, head: str) -> list[ChangedFileContext]:
    contexts: list[ChangedFileContext] = []
    for status, paths in changed_entries(repo, base, head):
        if status[:1] not in {"A", "M", "R", "C"}:
            continue
        path = paths[-1]
        if tree_mode(repo, head, path) not in {"100644", "100755"}:
            continue
        added_lines = _added_line_numbers(repo, base, head, path)
        if added_lines:
            contexts.append(
                ChangedFileContext(path, _blob_text(repo, head, path), added_lines)
            )
    return contexts


def _line_span(content: str, start: int, end: int) -> tuple[int, int]:
    first = content.count("\n", 0, start) + 1
    last_offset = start if end <= start else end - 1
    last = content.count("\n", 0, last_offset) + 1
    return first, last


def _overlaps_added_line(
    content: str, start: int, end: int, added_lines: frozenset[int]
) -> int | None:
    first, last = _line_span(content, start, end)
    return next((line for line in sorted(added_lines) if first <= line <= last), None)


def _sensitive_spans(content: str, path: str) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    for beginning in PRIVATE_KEY_BEGIN.finditer(content):
        ending = PRIVATE_KEY_END.search(content, beginning.end())
        spans.append((beginning.start(), ending.end() if ending else len(content)))
    for pattern in STANDALONE_SECRET_PATTERNS:
        spans.extend((match.start(), match.end()) for match in pattern.finditer(content))
    spans.extend(
        (match.start(), match.end())
        for match in CREDENTIAL_ASSIGNMENT.finditer(content)
        if is_sensitive_credential_assignment(match, path)
    )
    if PurePosixPath(path).suffix.lower() in {".yaml", ".yml"}:
        spans.extend(_yaml_block_spans(content))
    return spans


def _line_indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _yaml_block_parent_indent(header: re.Match[str]) -> int:
    return len(header.group("indent")) + len(header.group("sequence") or "")


def _yaml_block_spans(content: str) -> list[tuple[int, int]]:
    lines = content.splitlines(keepends=True)
    offsets: list[int] = []
    offset = 0
    for line in lines:
        offsets.append(offset)
        offset += len(line)
    spans: list[tuple[int, int]] = []
    index = 0
    while index < len(lines):
        header = YAML_BLOCK_CREDENTIAL_HEADER.match(lines[index])
        if header is None:
            index += 1
            continue
        header_indent = _yaml_block_parent_indent(header)
        end_index = index + 1
        while end_index < len(lines):
            candidate = lines[end_index]
            if candidate.strip() and _line_indent(candidate) <= header_indent:
                break
            end_index += 1
        end_offset = offsets[end_index] if end_index < len(lines) else len(content)
        spans.append((offsets[index], end_offset))
        index = end_index
    return spans


def _hunk_has_sensitive_content(content: str, path: str = "fixture.conf") -> bool:
    return bool(_sensitive_spans(content, path))


def check_sensitive_content(repo: Path, base: str, head: str) -> None:
    findings: list[str] = []
    for context in changed_file_contexts(repo, base, head):
        for start, end in _sensitive_spans(context.content, context.path):
            line = _overlaps_added_line(context.content, start, end, context.added_lines)
            if line is not None:
                findings.append(
                    f"{context.path}: added line {line}: possible credential or private key"
                )
                break
    if findings:
        raise GateError("\n".join(findings))
    print("checked added content for high-confidence credential patterns")


def _find_unescaped_quote(
    text: str, start: int, quote: str, escaped: bool = False
) -> tuple[int | None, bool]:
    for index in range(start, len(text)):
        character = text[index]
        if escaped:
            escaped = False
        elif character == "\\":
            escaped = True
        elif character == quote:
            return index, False
    return None, escaped


def redact_credentials(text: str) -> str:
    output: list[str] = []
    cursor = 0
    while quoted_start := CREDENTIAL_QUOTED_START.search(text, cursor):
        output.append(
            CREDENTIAL_ASSIGNMENT.sub(
                _redact_credential, text[cursor : quoted_start.start()]
            )
        )
        quote = quoted_start.group("value_quote")
        output.append(f"{quoted_start.group('prefix')}{quote}[REDACTED]{quote}")
        closing, _escaped = _find_unescaped_quote(text, quoted_start.end(), quote)
        if closing is None:
            return "".join(output)
        cursor = closing + 1
    output.append(CREDENTIAL_ASSIGNMENT.sub(_redact_credential, text[cursor:]))
    return "".join(output)


def redact_yaml_blocks(text: str) -> str:
    output: list[str] = []
    lines = text.splitlines(keepends=True)
    index = 0
    while index < len(lines):
        header = YAML_BLOCK_CREDENTIAL_HEADER.match(lines[index])
        if header is None:
            output.append(lines[index])
            index += 1
            continue
        ending = "\n" if lines[index].endswith("\n") else ""
        output.append(
            f"{header.group('indent')}{header.group('sequence') or ''}"
            f"{header.group('prefix')}[REDACTED]{ending}"
        )
        header_indent = _yaml_block_parent_indent(header)
        index += 1
        while index < len(lines):
            candidate = lines[index]
            if candidate.strip() and _line_indent(candidate) <= header_indent:
                break
            index += 1
    return "".join(output)


def redact(text: str) -> str:
    redacted_lines: list[str] = []
    inside_private_key = False
    for line in text.splitlines(keepends=True):
        if inside_private_key:
            if PRIVATE_KEY_END.search(line):
                inside_private_key = False
            continue
        if PRIVATE_KEY_BEGIN.search(line):
            ending = "\n" if line.endswith("\n") else ""
            redacted_lines.append(f"[REDACTED_PRIVATE_KEY_BLOCK]{ending}")
            inside_private_key = not bool(PRIVATE_KEY_END.search(line))
            continue
        redacted_lines.append(line)
    result = redact_credentials(redact_yaml_blocks("".join(redacted_lines)))
    for pattern, replacement in REDACTIONS:
        result = pattern.sub(replacement, result)
    return result


def ensure_pinned_checkout(repo: Path, head: str) -> None:
    checked_out = resolve_commit(repo, "HEAD", "head")
    if checked_out != head:
        raise GateError(f"resolved head {head} does not match checked-out HEAD {checked_out}")
    status_result = git(
        repo,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
        check=False,
    )
    if status_result.returncode != 0:
        raise GateError(status_result.stderr.strip() or "cannot inspect checkout cleanliness")
    if status_result.stdout:
        entries = [entry for entry in status_result.stdout.split("\0") if entry]
        bounded = ", ".join(entries[:20])
        if len(entries) > 20:
            bounded += f", ... ({len(entries) - 20} more)"
        raise GateError(f"checkout must be clean and contain no untracked files: {bounded}")
    flags_result = git(repo, "ls-files", "-v", "-z", check=False)
    if flags_result.returncode != 0:
        raise GateError(flags_result.stderr.strip() or "cannot inspect tracked index flags")
    flagged: list[str] = []
    for record in flags_result.stdout.split("\0"):
        if not record:
            continue
        tag, path = record[0], record[2:]
        if tag.islower() or tag == "S":
            flagged.append(f"{tag} {path}")
    if flagged:
        bounded = ", ".join(flagged[:20])
        if len(flagged) > 20:
            bounded += f", ... ({len(flagged) - 20} more)"
        raise GateError(f"assume-unchanged or skip-worktree index flags are forbidden: {bounded}")


def ensure_pinned_executable_inputs(repo: Path, head: str, command: list[str]) -> None:
    repository = repo.resolve(strict=True)
    checked: set[Path] = set()
    for argument in command:
        candidate = Path(argument)
        candidate = candidate if candidate.is_absolute() else repo / candidate
        try:
            resolved = candidate.resolve(strict=True)
        except (FileNotFoundError, OSError):
            continue
        if resolved in checked or not resolved.is_file() or not resolved.is_relative_to(repository):
            continue
        checked.add(resolved)
        relative = resolved.relative_to(repository).as_posix()
        listing = git(repo, "ls-tree", head, "--", relative, check=False)
        if listing.returncode != 0 or not listing.stdout.strip():
            raise GateError(f"executable input is not committed at recorded head: {relative}")
        metadata = listing.stdout.split("\t", 1)[0]
        mode, object_type, object_id = metadata.split(" ", 2)
        state = resolved.lstat()
        working_mode = "100755" if state.st_mode & 0o111 else "100644"
        if mode != "100755" or object_type != "blob" or working_mode != mode:
            raise GateError(f"executable input mode does not match pinned 100755 blob: {relative}")
        digest = git(repo, "hash-object", relative, check=False)
        if digest.returncode != 0 or digest.stdout.strip() != object_id:
            raise GateError(f"executable input content does not match recorded head: {relative}")


def materialize_pinned_checkout(source: Path, run_dir: Path, head: str) -> Path:
    destination = run_dir / "checkout"
    if _lstat(destination) is not None:
        raise GateError(f"unexpected snapshot path already exists: {destination}")
    old_umask = os.umask(0o077)
    try:
        try:
            result = subprocess.run(
                [
                    "git",
                    "clone",
                    "--quiet",
                    "--no-local",
                    "--no-checkout",
                    str(source),
                    str(destination),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
        except BaseException:
            if _lstat(destination) is not None:
                _remove_pinned_checkout(destination, run_dir)
            raise
    finally:
        os.umask(old_umask)
    if result.returncode != 0:
        if _lstat(destination) is not None:
            _remove_pinned_checkout(destination, run_dir)
        raise GateError(result.stderr.strip() or "cannot materialize pinned checkout")
    state = destination.lstat()
    if (
        stat.S_ISLNK(state.st_mode)
        or not stat.S_ISDIR(state.st_mode)
        or destination.resolve(strict=True).parent != run_dir.resolve(strict=True)
    ):
        raise GateError(f"unsafe materialized checkout: {destination}")
    checkout = git(destination, "checkout", "--quiet", "--detach", head, check=False)
    if checkout.returncode != 0:
        _remove_pinned_checkout(destination, run_dir)
        raise GateError(checkout.stderr.strip() or "cannot check out recorded head")
    ensure_pinned_checkout(destination, head)
    return destination


def _remove_pinned_checkout(checkout: Path, run_dir: Path) -> None:
    if checkout.parent != run_dir:
        raise GateError(f"unsafe snapshot parent: {checkout}")
    state = checkout.lstat()
    if stat.S_ISLNK(state.st_mode) or not stat.S_ISDIR(state.st_mode):
        raise GateError(f"snapshot path must be a real directory: {checkout}")
    if checkout.resolve(strict=True).parent != run_dir.resolve(strict=True):
        raise GateError(f"snapshot escapes evidence run: {checkout}")
    shutil.rmtree(checkout)


def _lstat(path: Path) -> os.stat_result | None:
    try:
        return path.lstat()
    except FileNotFoundError:
        return None


def _ensure_directory(path: Path, parent: Path) -> None:
    if path.parent != parent:
        raise GateError(f"unsafe evidence directory parent: {path}")
    state = _lstat(path)
    if state is None:
        path.mkdir(mode=0o700)
        state = path.lstat()
    if stat.S_ISLNK(state.st_mode) or not stat.S_ISDIR(state.st_mode):
        raise GateError(f"evidence path must be a real directory: {path}")
    if path.resolve(strict=True).parent != parent.resolve(strict=True):
        raise GateError(f"evidence directory escapes its validated parent: {path}")
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
            raise GateError(f"evidence descriptor is not a directory: {path}")
        os.fchmod(descriptor, 0o700)
    finally:
        os.close(descriptor)


def _validate_regular(path: Path, parent: Path) -> os.stat_result:
    if path.parent != parent:
        raise GateError(f"unsafe evidence file parent: {path}")
    state = path.lstat()
    if stat.S_ISLNK(state.st_mode) or not stat.S_ISREG(state.st_mode):
        raise GateError(f"evidence path must be a real regular file: {path}")
    if path.resolve(strict=True).parent != parent.resolve(strict=True):
        raise GateError(f"evidence file escapes its validated parent: {path}")
    parent_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    parent_descriptor = os.open(parent, parent_flags)
    try:
        descriptor = os.open(
            path.name,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_descriptor,
        )
    finally:
        os.close(parent_descriptor)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise GateError(f"evidence descriptor is not a regular file: {path}")
        os.fchmod(descriptor, 0o600)
    finally:
        os.close(descriptor)
    return state


def _open_new_private(path: Path, parent: Path):
    if path.parent != parent:
        raise GateError(f"unsafe evidence file parent: {path}")
    _ensure_directory(parent, parent.parent)
    parent_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    parent_descriptor = os.open(parent, parent_flags)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path.name, flags, 0o600, dir_fd=parent_descriptor)
    finally:
        os.close(parent_descriptor)
    state = os.fstat(descriptor)
    if not stat.S_ISREG(state.st_mode):
        os.close(descriptor)
        raise GateError(f"new evidence path is not a regular file: {path}")
    os.fchmod(descriptor, 0o600)
    return os.fdopen(descriptor, "w", encoding="utf-8")


def _open_existing_private(path: Path, parent: Path, mode: str):
    _validate_regular(path, parent)
    parent_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    parent_descriptor = os.open(parent, parent_flags)
    access_flags = os.O_RDONLY if mode == "rb" else os.O_WRONLY | os.O_APPEND
    try:
        descriptor = os.open(
            path.name,
            access_flags | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_descriptor,
        )
    finally:
        os.close(parent_descriptor)
    state = os.fstat(descriptor)
    if not stat.S_ISREG(state.st_mode):
        os.close(descriptor)
        raise GateError(f"evidence path is not a regular file: {path}")
    os.fchmod(descriptor, 0o600)
    if mode == "rb":
        return os.fdopen(descriptor, "rb")
    return os.fdopen(descriptor, "a", encoding="utf-8")


def _unlink_existing_private(path: Path, parent: Path) -> None:
    if _lstat(path) is None:
        return
    _validate_regular(path, parent)
    path.unlink()


def _validate_run_tree(run: Path, runs: Path) -> None:
    _ensure_directory(run, runs)
    for current_root, directory_names, file_names in os.walk(run, followlinks=False):
        current = Path(current_root)
        for name in [*directory_names, *file_names]:
            child = current / name
            state = child.lstat()
            if stat.S_ISLNK(state.st_mode):
                raise GateError(f"symlinked evidence run component is forbidden: {child}")
            if child.resolve(strict=True).is_relative_to(run.resolve(strict=True)) is False:
                raise GateError(f"evidence run component escapes run directory: {child}")


class Evidence:
    def __init__(self, repo: Path) -> None:
        self.repo = repo.resolve(strict=True)
        self.root = self.repo / ".quality-gate"
        self.runs = self.root / "runs"
        old_umask = os.umask(0o077)
        try:
            _ensure_directory(self.root, self.repo)
            _ensure_directory(self.runs, self.root)
            latest = self.root / "latest"
            if _lstat(latest) is not None:
                _validate_regular(latest, self.root)
            for existing in self.runs.iterdir():
                state = existing.lstat()
                if stat.S_ISLNK(state.st_mode) or not stat.S_ISDIR(state.st_mode):
                    raise GateError(f"invalid evidence run component: {existing}")
                _validate_run_tree(existing, self.runs)
            stamp = dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%S.%fZ")
            self.run_id = f"{stamp}-{os.getpid()}"
            self.run_dir = self.runs / self.run_id
            _ensure_directory(self.run_dir, self.runs)
            self.log_path = self.run_dir / "full.log"
            self.summary_path = self.run_dir / "summary.txt"
            with _open_new_private(self.log_path, self.run_dir):
                pass
            self._update_latest()
        finally:
            os.umask(old_umask)

    def _update_latest(self) -> None:
        temporary = self.root / f".latest-{os.getpid()}"
        if _lstat(temporary) is not None:
            raise GateError(f"unexpected evidence pointer temporary exists: {temporary}")
        with _open_new_private(temporary, self.root) as pointer:
            pointer.write(f"{self.run_id}\n")
        latest = self.root / "latest"
        if _lstat(latest) is not None:
            _validate_regular(latest, self.root)
        os.replace(temporary, self.root / "latest")
        _validate_regular(latest, self.root)

    @staticmethod
    def _encode_summary(summary: str) -> bytes:
        encoded = redact(summary).encode("utf-8", "replace")[:MAX_SUMMARY_BYTES]
        if len(encoded) == MAX_SUMMARY_BYTES:
            encoded = encoded.rsplit(b"\n", 1)[0] + b"\n[summary truncated]\n"
        return encoded

    def complete(self, summary: str) -> None:
        encoded = self._encode_summary(summary)
        with _open_new_private(self.summary_path, self.run_dir) as summary_file:
            summary_file.buffer.write(encoded)
        marker = self.run_dir / "completed"
        with _open_new_private(marker, self.run_dir) as completed_file:
            completed_file.write("complete\n")
        self.prune()

    def replace_summary(self, summary: str) -> None:
        _validate_regular(self.summary_path, self.run_dir)
        temporary = self.run_dir / f".summary-{os.getpid()}"
        with _open_new_private(temporary, self.run_dir) as summary_file:
            summary_file.buffer.write(self._encode_summary(summary))
        os.replace(temporary, self.summary_path)
        _validate_regular(self.summary_path, self.run_dir)

    def prune(self) -> None:
        completed: list[Path] = []
        for path in sorted(self.runs.iterdir()):
            _validate_run_tree(path, self.runs)
            marker = path / "completed"
            if _lstat(marker) is not None:
                _validate_regular(marker, path)
                completed.append(path)
        removable = [path for path in completed if path != self.run_dir]
        while len(completed) > MAX_COMPLETED_RUNS and removable:
            victim = removable.pop(0)
            _validate_run_tree(victim, self.runs)
            shutil.rmtree(victim)
            completed.remove(victim)


class GateInterrupted(GateError):
    def __init__(self, signum: int) -> None:
        super().__init__(f"interrupted by {signal.Signals(signum).name}")
        self.signum = signum


class GateSignalState:
    def __init__(self) -> None:
        self.first_signum: int | None = None
        self.interruptible = True

    def handle(self, signum: int) -> None:
        if self.first_signum is not None:
            return
        self.first_signum = signum
        if self.interruptible:
            raise GateInterrupted(signum)


def consume_pending_watched_signals(
    signal_state: GateSignalState, watched_signals: tuple[signal.Signals, ...]
) -> None:
    """Consume blocked watched signals so restoring defaults cannot lose the first one."""
    watched = {int(signum) for signum in watched_signals}
    while True:
        pending = {int(signum) for signum in signal.sigpending()} & watched
        if not pending:
            return
        consumed = signal.sigwait({min(pending)})
        signal_state.handle(int(consumed))


PENDING_CREDENTIAL_KEY = re.compile(
    rf"(?i)(?P<key_quote>['\"]?)\b{CREDENTIAL_KEY}\b(?P=key_quote)\s*[:=]\s*$"
)
CREDENTIAL_PREFIX = re.compile(
    rf"(?i)['\"]?\b{CREDENTIAL_KEY}\b['\"]?\s*[:=]"
)


class OpenCredentialQuote:
    def __init__(self, quote: str) -> None:
        self.quote = quote
        self.escaped = False
        self.scanned_chars = 0
        self.limit_reported = False


class OpenYamlCredentialBlock:
    def __init__(self, header_indent: int) -> None:
        self.header_indent = header_indent
        self.scanned_chars = 0
        self.limit_reported = False


class StreamingRedactor:
    def __init__(self, max_lines: int = MAX_FAILURE_LINES) -> None:
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.buffer = ""
        self.pending_credential = ""
        self.inside_private_key = False
        self.open_credential_quote: OpenCredentialQuote | None = None
        self.open_yaml_block: OpenYamlCredentialBlock | None = None
        self.oversized_preview: str | None = None
        self.discarding_sensitive_line = False
        self.lines: deque[str] = deque(maxlen=max_lines)

    def feed(self, content: bytes) -> None:
        self.buffer += self.decoder.decode(content)
        self._drain()

    def finish(self) -> list[str]:
        self.buffer += self.decoder.decode(b"", final=True)
        self._drain(final=True)
        if self.open_credential_quote is not None:
            self._append_line("[REDACTED_CREDENTIAL_BLOCK_UNTERMINATED]")
            self.open_credential_quote = None
        if self.open_yaml_block is not None:
            self._append_line("[REDACTED_YAML_BLOCK_UNTERMINATED]")
            self.open_yaml_block = None
        if self.pending_credential:
            self._append_redacted(self.pending_credential)
            self.pending_credential = ""
        return list(self.lines)

    def _drain(self, final: bool = False) -> None:
        while self.buffer:
            if self.inside_private_key:
                ending = PRIVATE_KEY_END.search(self.buffer)
                if ending is None:
                    if len(self.buffer) > 256:
                        self.buffer = self.buffer[-256:]
                    return
                self.buffer = self.buffer[ending.end() :]
                self.inside_private_key = False
                continue

            beginning = PRIVATE_KEY_BEGIN.search(self.buffer)
            newline = self.buffer.find("\n")
            if beginning is not None and (newline < 0 or beginning.start() <= newline):
                prefix = self.buffer[: beginning.start()]
                if prefix or self.oversized_preview is not None:
                    self._finish_oversized_line(prefix)
                self._append_line("[REDACTED_PRIVATE_KEY_BLOCK]")
                self.buffer = self.buffer[beginning.end() :]
                self.inside_private_key = PRIVATE_KEY_END.search(self.buffer) is None
                if not self.inside_private_key:
                    ending = PRIVATE_KEY_END.search(self.buffer)
                    assert ending is not None
                    self.buffer = self.buffer[ending.end() :]
                continue

            if newline < 0:
                if final:
                    self._finish_oversized_line(self.buffer)
                    self.buffer = ""
                elif len(self.buffer) > MAX_STREAM_BUFFER_CHARS:
                    self._consume_oversized_fragment()
                return
            line = self.buffer[: newline + 1]
            self.buffer = self.buffer[newline + 1 :]
            self._finish_oversized_line(line)

    def _consume_oversized_fragment(self) -> None:
        split_at = len(self.buffer) - STREAM_PATTERN_OVERLAP
        fragment = self.buffer[:split_at]
        self.buffer = self.buffer[split_at:]
        if self.open_yaml_block is not None:
            self._consume_yaml_payload(fragment)
            self.oversized_preview = "[REDACTED_YAML_BLOCK_CONTENT]"
            self.discarding_sensitive_line = True
            return
        if self.open_credential_quote is not None:
            self._consume_open_credential(fragment)
            return
        combined = self.pending_credential + fragment
        if self._begin_open_quoted_credential(combined):
            self.pending_credential = ""
            self.oversized_preview = None
            self.discarding_sensitive_line = False
            return
        if (
            self.pending_credential
            or self.discarding_sensitive_line
            or CREDENTIAL_PREFIX.search(fragment)
        ):
            self.pending_credential = ""
            self.discarding_sensitive_line = True
            self.oversized_preview = "[REDACTED]"
            return
        if self.oversized_preview is None:
            self.oversized_preview = ""
        remaining = MAX_REDACTED_LINE_CHARS - len(self.oversized_preview)
        if remaining > 0:
            self.oversized_preview += redact(fragment)[:remaining]

    def _finish_oversized_line(self, line: str) -> None:
        if self.oversized_preview is None:
            self._process_line(line)
            return
        if CREDENTIAL_PREFIX.search(line):
            self.discarding_sensitive_line = True
            self.oversized_preview = "[REDACTED]"
        if not self.discarding_sensitive_line:
            remaining = MAX_REDACTED_LINE_CHARS - len(self.oversized_preview)
            if remaining > 0:
                self.oversized_preview += redact(line)[:remaining]
        self._append_line(self.oversized_preview)
        self.oversized_preview = None
        self.discarding_sensitive_line = False

    def _process_line(self, line: str) -> None:
        if self.open_yaml_block is not None:
            if not line.strip() or _line_indent(line) > self.open_yaml_block.header_indent:
                self._consume_yaml_payload(line)
                return
            self.open_yaml_block = None
        if self.open_credential_quote is not None:
            self._consume_open_credential(line)
            return
        if self.pending_credential:
            combined = self.pending_credential + line
            if self._begin_open_quoted_credential(combined):
                self.pending_credential = ""
                return
            if CREDENTIAL_ASSIGNMENT.search(combined):
                self._append_redacted(combined)
                self.pending_credential = ""
                return
            if line.strip() == "":
                if len(combined) <= MAX_STREAM_BUFFER_CHARS:
                    self.pending_credential = combined
                return
            self._append_redacted(self.pending_credential)
            self.pending_credential = ""
        if self._begin_open_quoted_credential(line):
            return
        yaml_header = YAML_BLOCK_CREDENTIAL_HEADER.match(line)
        if yaml_header is not None:
            self._append_line(
                f"{yaml_header.group('indent')}"
                f"{yaml_header.group('sequence') or ''}"
                f"{yaml_header.group('prefix')}[REDACTED]"
            )
            self.open_yaml_block = OpenYamlCredentialBlock(
                _yaml_block_parent_indent(yaml_header)
            )
            return
        if PENDING_CREDENTIAL_KEY.search(line.rstrip("\r\n")):
            self.pending_credential = line
            return
        self._append_redacted(line)

    def _begin_open_quoted_credential(self, text: str) -> bool:
        cursor = 0
        while quoted_start := CREDENTIAL_QUOTED_START.search(text, cursor):
            quote = quoted_start.group("value_quote")
            closing, _escaped = _find_unescaped_quote(text, quoted_start.end(), quote)
            if closing is not None:
                cursor = closing + 1
                continue
            safe_prefix = (
                text[: quoted_start.start()]
                + quoted_start.group("prefix")
                + quote
                + "[REDACTED]"
                + quote
            )
            self._append_redacted(safe_prefix)
            self.open_credential_quote = OpenCredentialQuote(quote)
            self._consume_open_credential(text[quoted_start.end() :])
            return True
        return False

    def _consume_open_credential(self, text: str) -> None:
        state = self.open_credential_quote
        assert state is not None
        closing, escaped = _find_unescaped_quote(text, 0, state.quote, state.escaped)
        consumed = len(text) if closing is None else closing + 1
        state.scanned_chars = min(
            MAX_OPEN_CREDENTIAL_CHARS + 1, state.scanned_chars + consumed
        )
        if state.scanned_chars > MAX_OPEN_CREDENTIAL_CHARS and not state.limit_reported:
            self._append_line("[REDACTED_CREDENTIAL_BLOCK_LIMIT_EXCEEDED]")
            state.limit_reported = True
        state.escaped = escaped
        if closing is None:
            return
        self.open_credential_quote = None
        suffix = text[closing + 1 :]
        if suffix:
            self._process_line(suffix)

    def _consume_yaml_payload(self, text: str) -> None:
        state = self.open_yaml_block
        assert state is not None
        state.scanned_chars = min(
            MAX_OPEN_CREDENTIAL_CHARS + 1, state.scanned_chars + len(text)
        )
        if state.scanned_chars > MAX_OPEN_CREDENTIAL_CHARS and not state.limit_reported:
            self._append_line("[REDACTED_YAML_BLOCK_LIMIT_EXCEEDED]")
            state.limit_reported = True

    def _append_redacted(self, text: str) -> None:
        for line in redact(text).splitlines():
            if line:
                self._append_line(line)

    def _append_line(self, line: str) -> None:
        if len(line) > MAX_REDACTED_LINE_CHARS:
            line = line[:MAX_REDACTED_LINE_CHARS] + " [line truncated]"
        self.lines.append(line)


def redacted_tail(path: Path, max_lines: int = MAX_FAILURE_LINES) -> list[str]:
    redactor = StreamingRedactor(max_lines)
    with _open_existing_private(path, path.parent, "rb") as stream:
        while content := stream.read(8192):
            redactor.feed(content)
    return redactor.finish()


def _process_group_exists(process_group: int) -> bool:
    try:
        os.killpg(process_group, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


class ProcessIdentity(NamedTuple):
    pid: int
    started: tuple[int, int] | str


if sys.platform == "darwin":

    class _DarwinProcessInfo(ctypes.Structure):
        _fields_ = [
            ("flags", ctypes.c_uint32),
            ("status", ctypes.c_uint32),
            ("xstatus", ctypes.c_uint32),
            ("pid", ctypes.c_uint32),
            ("ppid", ctypes.c_uint32),
            ("uid", ctypes.c_uint32),
            ("gid", ctypes.c_uint32),
            ("ruid", ctypes.c_uint32),
            ("rgid", ctypes.c_uint32),
            ("svuid", ctypes.c_uint32),
            ("svgid", ctypes.c_uint32),
            ("reserved", ctypes.c_uint32),
            ("command", ctypes.c_char * 16),
            ("name", ctypes.c_char * 32),
            ("file_count", ctypes.c_uint32),
            ("process_group", ctypes.c_uint32),
            ("job_control", ctypes.c_uint32),
            ("terminal_device", ctypes.c_uint32),
            ("terminal_group", ctypes.c_uint32),
            ("nice", ctypes.c_int32),
            ("start_seconds", ctypes.c_uint64),
            ("start_microseconds", ctypes.c_uint64),
        ]

    _DARWIN_LIBPROC = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)


def _process_identity(pid: int) -> ProcessIdentity | None:
    if sys.platform == "darwin":
        info = _DarwinProcessInfo()
        size = _DARWIN_LIBPROC.proc_pidinfo(
            pid, 3, 0, ctypes.byref(info), ctypes.sizeof(info)
        )
        if size != ctypes.sizeof(info):
            return None
        return ProcessIdentity(pid, (info.start_seconds, info.start_microseconds))
    if sys.platform.startswith("linux"):
        try:
            fields = (Path("/proc") / str(pid) / "stat").read_text().split()
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            return None
        return ProcessIdentity(pid, fields[21])
    raise GateError(f"descendant containment is unsupported on {sys.platform}")


def _direct_child_pids(pid: int) -> list[int]:
    if sys.platform == "darwin":
        buffer = (ctypes.c_int * 4096)()
        byte_count = _DARWIN_LIBPROC.proc_listchildpids(
            pid, buffer, ctypes.sizeof(buffer)
        )
        count = max(0, byte_count) // ctypes.sizeof(ctypes.c_int)
        return [value for value in buffer[:count] if value]
    if sys.platform.startswith("linux"):
        children = Path("/proc") / str(pid) / "task" / str(pid) / "children"
        try:
            return [int(value) for value in children.read_text().split()]
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            return []
    raise GateError(f"descendant containment is unsupported on {sys.platform}")


def _path_holder_pids(path: Path) -> list[int]:
    if sys.platform == "darwin":
        buffer = (ctypes.c_int * 16384)()
        byte_count = _DARWIN_LIBPROC.proc_listpidspath(
            1, 0, os.fsencode(path), 0, buffer, ctypes.sizeof(buffer)
        )
        count = max(0, byte_count) // ctypes.sizeof(ctypes.c_int)
        return [value for value in buffer[:count] if value]
    if sys.platform.startswith("linux"):
        expected = path.resolve(strict=True)
        holders: list[int] = []
        for process_dir in Path("/proc").glob("[0-9]*"):
            try:
                if any(
                    descriptor.resolve(strict=True) == expected
                    for descriptor in (process_dir / "fd").iterdir()
                ):
                    holders.append(int(process_dir.name))
            except (FileNotFoundError, PermissionError, ProcessLookupError):
                continue
        return holders
    raise GateError(f"descendant containment is unsupported on {sys.platform}")


class DescendantTracker:
    """Track ordinary descendants of trusted checks for lifecycle cleanup.

    This supplements process-group cleanup; it is not an adversarial sandbox.
    """

    def __init__(self, root_pid: int, inherited_path: Path) -> None:
        root = _process_identity(root_pid)
        self.root_pid = root_pid
        self.inherited_path = inherited_path
        self.identities: dict[int, ProcessIdentity] = (
            {root.pid: root} if root is not None else {}
        )
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._monitor, daemon=True)

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        self.thread.join(timeout=1)

    def _monitor(self) -> None:
        while not self.stop_event.is_set():
            self.refresh()
            self.stop_event.wait(0.02)

    def refresh(self) -> None:
        with self.lock:
            parents = list(self.identities.values())
        discovered: list[ProcessIdentity] = []
        for parent in parents:
            if _process_identity(parent.pid) != parent:
                continue
            for pid in _direct_child_pids(parent.pid):
                identity = _process_identity(pid)
                if identity is not None:
                    discovered.append(identity)
        if discovered:
            with self.lock:
                self.identities.update((identity.pid, identity) for identity in discovered)

    def capture_inherited_path_holders(self) -> None:
        discovered = []
        for pid in _path_holder_pids(self.inherited_path):
            if pid == os.getpid():
                continue
            identity = _process_identity(pid)
            if identity is not None:
                discovered.append(identity)
        if discovered:
            with self.lock:
                self.identities.update((identity.pid, identity) for identity in discovered)

    def live(self) -> list[ProcessIdentity]:
        self.refresh()
        self.capture_inherited_path_holders()
        with self.lock:
            identities = list(self.identities.values())
        return [
            identity
            for identity in identities
            if identity.pid != os.getpid()
            and _process_identity(identity.pid) == identity
        ]

    def signal(self, signum: int) -> None:
        for identity in self.live():
            try:
                os.kill(identity.pid, signum)
            except ProcessLookupError:
                pass


def _wait_for_containment_exit(
    process: subprocess.Popen[str],
    process_group: int,
    descendants: DescendantTracker,
    timeout: float,
) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        process.poll()
        if not _process_group_exists(process_group) and not descendants.live():
            return True
        time.sleep(0.05)
    process.poll()
    return not _process_group_exists(process_group) and not descendants.live()


def _terminate_process_containment(
    process: subprocess.Popen[str],
    process_group: int,
    descendants: DescendantTracker,
    signum: int = signal.SIGTERM,
) -> None:
    cleanup_error: GateError | None = None
    if _process_group_exists(process_group):
        try:
            os.killpg(process_group, signum)
        except ProcessLookupError:
            pass
    descendants.signal(signum)
    if not _wait_for_containment_exit(
        process, process_group, descendants, PROCESS_GROUP_TERM_SECONDS
    ):
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
        descendants.signal(signal.SIGKILL)
        if not _wait_for_containment_exit(
            process, process_group, descendants, PROCESS_GROUP_KILL_SECONDS
        ):
            survivor_pids = ", ".join(str(item.pid) for item in descendants.live())
            cleanup_error = GateError(
                f"process containment did not exit after SIGKILL: {survivor_pids}"
            )
    try:
        process.wait(timeout=PROCESS_GROUP_KILL_SECONDS)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    if cleanup_error is not None:
        raise cleanup_error


class Runner:
    def __init__(self, evidence: Evidence, repo: Path) -> None:
        self.evidence = evidence
        self.repo = repo
        self.results: list[tuple[str, int, list[str]]] = []

    def step(self, name: str, command: list[str], env: dict[str, str] | None = None) -> bool:
        process: subprocess.Popen[str] | None = None
        process_group: int | None = None
        descendants: DescendantTracker | None = None
        guard_path = self.evidence.run_dir / f".process-{len(self.results):03d}.guard"
        with contextlib.ExitStack() as resources:
            resources.callback(
                _unlink_existing_private, guard_path, self.evidence.run_dir
            )
            guard = resources.enter_context(
                _open_new_private(guard_path, self.evidence.run_dir)
            )
            log = resources.enter_context(
                _open_existing_private(self.evidence.log_path, self.evidence.run_dir, "a")
            )
            log.write(f"\n=== {name} ===\n$ {shlex.join(command)}\n")
            log.flush()
            try:
                previous_mask = signal.pthread_sigmask(
                    signal.SIG_BLOCK, {signal.SIGINT, signal.SIGTERM}
                )
                try:
                    process = subprocess.Popen(
                        command,
                        cwd=self.repo,
                        env=env,
                        text=True,
                        stdout=log,
                        stderr=subprocess.STDOUT,
                        start_new_session=True,
                        preexec_fn=_unblock_watched_signals,
                        pass_fds=(guard.fileno(),),
                    )
                    process_group = os.getpgid(process.pid)
                    descendants = DescendantTracker(process.pid, guard_path)
                    descendants.start()
                finally:
                    signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
                return_code = process.wait()
            except GateInterrupted as interrupted:
                if (
                    process is not None
                    and process_group is not None
                    and descendants is not None
                ):
                    try:
                        _terminate_process_containment(
                            process, process_group, descendants, interrupted.signum
                        )
                    finally:
                        descendants.stop()
                self.results.append(
                    (name, 128 + interrupted.signum, redacted_tail(self.evidence.log_path))
                )
                raise
            except BaseException:
                if (
                    process is not None
                    and process_group is not None
                    and descendants is not None
                ):
                    try:
                        _terminate_process_containment(process, process_group, descendants)
                    finally:
                        descendants.stop()
                raise
            assert process_group is not None
            assert descendants is not None
            escaped = descendants.live()
            if _process_group_exists(process_group) or escaped:
                escaped_pids = ", ".join(str(item.pid) for item in escaped) or "none"
                log.write(
                    f"step left contained processes running; pgid={process_group}; "
                    f"descendants={escaped_pids}; terminating them\n"
                )
                log.flush()
                try:
                    _terminate_process_containment(process, process_group, descendants)
                finally:
                    descendants.stop()
                if return_code == 0:
                    return_code = 1
            else:
                descendants.stop()
        excerpt = redacted_tail(self.evidence.log_path) if return_code else []
        self.results.append((name, return_code, excerpt))
        return return_code == 0


def run_pinned_step(
    runner: Runner,
    repo: Path,
    head: str,
    name: str,
    command: list[str],
    env: dict[str, str] | None = None,
) -> bool:
    ensure_pinned_checkout(repo, head)
    ensure_pinned_executable_inputs(repo, head, command)
    try:
        return runner.step(name, command, env)
    finally:
        try:
            ensure_pinned_checkout(repo, head)
        except (GateError, OSError) as error:
            if runner.results and runner.results[-1][0] == name:
                recorded_name, recorded_code, recorded_excerpt = runner.results[-1]
                runner.results[-1] = (
                    recorded_name,
                    recorded_code or 1,
                    [*recorded_excerpt, f"input integrity failure: {error}"][
                        -MAX_FAILURE_LINES:
                    ],
                )
            raise


def _unblock_watched_signals() -> None:
    signal.pthread_sigmask(signal.SIG_UNBLOCK, {signal.SIGINT, signal.SIGTERM})


def extension_commands(repo: Path, head: str, groups: tuple[str, ...]) -> list[tuple[str, list[str]]]:
    commands: list[tuple[str, list[str]]] = []
    for group in groups:
        prefix = f"tools/quality/checks.d/{group}"
        listing = git(repo, "ls-tree", "-r", "-z", head, "--", prefix, check=False)
        if listing.returncode != 0:
            raise GateError(listing.stderr.strip() or f"cannot inspect {prefix} extensions")
        records = [item for item in listing.stdout.split("\0") if item]
        for record in sorted(records, key=lambda item: item.partition("\t")[2]):
            metadata, relative = record.split("\t", 1)
            mode, object_type, _object_id = metadata.split(" ", 2)
            relative_path = PurePosixPath(relative)
            if relative_path.parent.as_posix() != prefix or relative_path.name.startswith("."):
                continue
            if mode != "100755" or object_type != "blob":
                raise GateError(f"committed extension must be a regular 100755 file: {relative}")
            path = repo / relative
            state = path.lstat()
            if stat.S_ISLNK(state.st_mode) or not stat.S_ISREG(state.st_mode):
                raise GateError(f"extension working path must be a regular file: {relative}")
            if not path.resolve(strict=True).is_relative_to(repo.resolve(strict=True)):
                raise GateError(f"extension escapes repository: {relative}")
            if not os.access(path, os.X_OK):
                raise GateError(f"extension working path is not executable: {relative}")
            commands.append((f"extension:{group}/{relative_path.name}", [str(path)]))
    return commands


def internal_check(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("check", choices=("markdown-links", "skill-structure", "sensitive"))
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    options = parser.parse_args(arguments)
    repo = Path(options.repo)
    try:
        entries = changed_entries(repo, options.base, options.head)
        if options.check == "markdown-links":
            check_markdown_links(repo, options.head, changed_markdown_paths(entries))
        elif options.check == "skill-structure":
            check_skill_structure(repo, options.head)
        else:
            check_sensitive_content(repo, options.base, options.head)
    except GateError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


def gate(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="quality-gate.sh",
        description="Run the repository quality gate (auto or forced normal).",
    )
    parser.add_argument("mode", nargs="?", default="auto", choices=("auto", "normal"))
    parser.add_argument("--base", help="required fixed comparison revision")
    parser.add_argument("--head", default="HEAD", help="head revision (default: HEAD)")
    parser.add_argument("--ref", default="manual", help="descriptive ref recorded in evidence")
    options = parser.parse_args(arguments)

    source_repo = Path(__file__).resolve().parents[2]
    try:
        evidence = Evidence(source_repo)
    except (GateError, OSError) as error:
        print(f"quality gate cannot create safe evidence: {error}", file=sys.stderr)
        return 1
    base_display = options.base or "UNRESOLVED(missing)"
    head_display = options.head
    profile = "normal"
    reason = "fail-closed before classification"
    runner = Runner(evidence, source_repo)
    checkout: Path | None = None
    failure: str | None = None
    interrupted_signum: int | None = None
    watched_signals = (signal.SIGINT, signal.SIGTERM)
    signal_state = GateSignalState()
    previous_signal_mask = signal.pthread_sigmask(signal.SIG_UNBLOCK, set(watched_signals))
    previous_handlers = {signum: signal.getsignal(signum) for signum in watched_signals}

    def interrupt(signum: int, _frame: object) -> None:
        signal_state.handle(signum)

    for signum in watched_signals:
        signal.signal(signum, interrupt)
    try:
        base = resolve_commit(source_repo, options.base or "", "base")
        head = resolve_commit(source_repo, options.head, "head")
        base_display = base
        head_display = head
        ensure_pinned_checkout(source_repo, head)
        selected, classified_reason, entries = classify(source_repo, base, head)
        if options.mode == "normal":
            profile = "normal"
            reason = f"caller forced normal; classifier observed {classified_reason}"
        else:
            profile = selected
            reason = classified_reason

        checkout = materialize_pinned_checkout(source_repo, evidence.run_dir, head)
        runner = Runner(evidence, checkout)
        environment = os.environ.copy()
        environment.update(
            {
                "QUALITY_GATE_BASE": base,
                "QUALITY_GATE_HEAD": head,
                "QUALITY_GATE_REF": options.ref,
                "QUALITY_GATE_PROFILE": profile,
                "QUALITY_GATE_RUN_DIR": str(evidence.run_dir),
            }
        )
        core = str(checkout / "tools/quality/quality_gate.py")
        common_extensions = extension_commands(checkout, head, ("common",))
        normal_extensions = (
            extension_commands(checkout, head, ("normal",))
            if profile == "normal"
            else []
        )
        steps: list[tuple[str, list[str], dict[str, str] | None]] = [
            (
                "gate-self-tests",
                [str(checkout / "tools/quality/test/run-tests.sh")],
                environment,
            ),
            ("diff-whitespace", ["git", "diff", "--check", base, head], environment),
            (
                "markdown-local-links",
                [
                    sys.executable,
                    core,
                    "_check",
                    "markdown-links",
                    "--repo",
                    str(checkout),
                    "--base",
                    base,
                    "--head",
                    head,
                ],
                environment,
            ),
            (
                "project-skill-structure",
                [
                    sys.executable,
                    core,
                    "_check",
                    "skill-structure",
                    "--repo",
                    str(checkout),
                    "--base",
                    base,
                    "--head",
                    head,
                ],
                environment,
            ),
            (
                "sensitive-content",
                [
                    sys.executable,
                    core,
                    "_check",
                    "sensitive",
                    "--repo",
                    str(checkout),
                    "--base",
                    base,
                    "--head",
                    head,
                ],
                environment,
            ),
        ]
        steps.extend(
            (name, command, environment)
            for name, command in common_extensions
        )
        for name, command, env in steps:
            if not run_pinned_step(runner, checkout, head, name, command, env):
                failure = f"step failed: {name}"
                break
        if failure is None and profile == "normal":
            if not run_pinned_step(
                runner,
                checkout,
                head,
                "maven-clean-verify",
                ["./mvnw", "-B", "-ntp", "clean", "verify"],
                environment,
            ):
                failure = "step failed: maven-clean-verify"
            else:
                for name, command in normal_extensions:
                    if not run_pinned_step(
                        runner, checkout, head, name, command, environment
                    ):
                        failure = f"step failed: {name}"
                        break
        del entries
    except GateInterrupted as interrupted:
        interrupted_signum = interrupted.signum
        failure = str(interrupted)
    except (GateError, OSError) as error:
        failure = str(error)
    signal_state.interruptible = False
    if failure is None and checkout is not None:
        try:
            ensure_pinned_checkout(checkout, head)
        except (GateError, OSError) as error:
            failure = str(error)
    if checkout is not None and _lstat(checkout) is not None:
        try:
            _remove_pinned_checkout(checkout, evidence.run_dir)
        except (GateError, OSError) as error:
            if failure is None:
                failure = str(error)

    def absorb_signal() -> None:
        nonlocal failure, interrupted_signum
        if signal_state.first_signum is not None:
            interrupted_signum = signal_state.first_signum
            failure = str(GateInterrupted(interrupted_signum))

    def render_summary() -> str:
        status_text = (
            "INTERRUPTED"
            if interrupted_signum is not None
            else ("PASS" if failure is None else "FAIL")
        )
        lines = [
            f"status: {status_text}",
            f"mode: {options.mode}",
            f"profile: {profile}",
            f"reason: {reason}",
            f"base: {base_display}",
            f"head: {head_display}",
            f"ref: {options.ref}",
            f"evidence: {evidence.run_dir}",
            "steps:",
        ]
        for name, code, excerpt in runner.results:
            lines.append(f"- {name}: {'PASS' if code == 0 else f'FAIL ({code})'}")
            if code:
                lines.append("  bounded-redacted-tail:")
                lines.extend(f"  | {line}" for line in excerpt)
        if failure:
            lines.append(f"failure: {failure}")
        return "\n".join(lines) + "\n"

    def refresh_interrupted_summary() -> None:
        while True:
            observed = signal_state.first_signum
            before_refresh = interrupted_signum
            absorb_signal()
            if (
                interrupted_signum != before_refresh
                and _lstat(evidence.summary_path) is not None
            ):
                evidence.replace_summary(render_summary())
            if signal_state.first_signum == observed:
                return

    absorb_signal()
    summary = render_summary()
    finalization_mask: set[signal.Signals] | None = None
    handlers_restored = False
    try:
        evidence.complete(summary)
        finalization_mask = signal.pthread_sigmask(
            signal.SIG_BLOCK, set(watched_signals)
        )
        consume_pending_watched_signals(signal_state, watched_signals)
        refresh_interrupted_summary()

        # Unblock while the gate handlers are still installed. A signal pending
        # in, or arriving during, this handoff is therefore recorded before the
        # caller's handlers and mask are restored.
        handoff_mask = set(finalization_mask) - set(watched_signals)
        signal.pthread_sigmask(signal.SIG_SETMASK, handoff_mask)
        refresh_interrupted_summary()
        before_handler_restore = signal_state.first_signum
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
        handlers_restored = True
        if signal_state.first_signum != before_handler_restore:
            refresh_interrupted_summary()
    finally:
        if not handlers_restored:
            signal.pthread_sigmask(signal.SIG_BLOCK, set(watched_signals))
            consume_pending_watched_signals(signal_state, watched_signals)
            refresh_interrupted_summary()
            blocked_mask = signal.pthread_sigmask(signal.SIG_BLOCK, set())
            signal.pthread_sigmask(
                signal.SIG_SETMASK,
                set(blocked_mask) - set(watched_signals),
            )
            refresh_interrupted_summary()
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_signal_mask)
    with _open_existing_private(evidence.summary_path, evidence.run_dir, "rb") as summary_file:
        print(summary_file.read().decode("utf-8", "replace"), end="")
    if interrupted_signum:
        return 128 + interrupted_signum
    return 0 if failure is None else 1


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "_check":
        return internal_check(sys.argv[2:])
    return gate(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
