#!/usr/bin/env python3
"""Validate the repository-owned Markdown tracker."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ALLOWED_STATUSES = {
    "spec": {
        "needs-triage",
        "needs-info",
        "ready-for-agent",
        "ready-for-human",
        "wontfix",
        "resolved",
    },
    "issue": {
        "needs-triage",
        "needs-info",
        "ready-for-agent",
        "ready-for-human",
        "wontfix",
        "claimed",
        "blocked",
        "resolved",
    },
    "finding": {
        "needs-triage",
        "needs-info",
        "verifying",
        "confirmed",
        "rejected",
        "wontfix",
    },
    "review": {"open", "verifying", "planned", "closed"},
}
TERMINAL_ISSUE_STATUSES = {"resolved", "wontfix"}
METADATA = re.compile(
    r"^(?:\*\*)?(Type|Status|Blocked by):(?:\*\*)?\s*(.*?)\s*$",
    re.MULTILINE | re.IGNORECASE,
)
ISSUE_NUMBER = re.compile(r"^(\d{2})-")
ANSWER = re.compile(r"^## Answer\s*$|^\*\*Answer:\*\*", re.MULTILINE | re.IGNORECASE)
VERIFICATION_EVIDENCE = re.compile(
    r"verification|verify|test|evidence|commit|验证|测试|审查|通过|提交",
    re.IGNORECASE,
)
UNCHECKED = re.compile(r"^\s*[-*]\s*\[\s\]\s+", re.MULTILINE)


@dataclass(frozen=True)
class Record:
    path: Path
    relative: Path
    kind: str
    status: str
    text: str
    blockers: tuple[str, ...]
    external_blockers: int


def inferred_kind(relative: Path) -> str | None:
    if relative.name == "spec.md" and len(relative.parts) == 3:
        return "spec"
    if len(relative.parts) == 4 and relative.parts[2] == "issues":
        return "issue"
    if len(relative.parts) == 4 and relative.parts[2] == "findings":
        return "finding"
    if relative.name == "review.md" and len(relative.parts) == 3:
        return "review"
    return None


def metadata_values(text: str) -> dict[str, list[tuple[str, int]]]:
    values: dict[str, list[tuple[str, int]]] = {}
    for match in METADATA.finditer(text):
        key = match.group(1).lower()
        line = text.count("\n", 0, match.start()) + 1
        values.setdefault(key, []).append((match.group(2).strip(), line))
    return values


def parse_blockers(
    value: str, location: str, errors: list[str]
) -> tuple[tuple[str, ...], int]:
    if re.match(r"^none(?:\s|$|[—-])", value, re.IGNORECASE):
        return (), 0
    blockers: list[str] = []
    external = 0
    for item in re.split(r"[,;；]", value):
        match = re.match(r"\s*(\d{1,2})(?=\s|$|[—-])", item)
        if re.match(r"\s*External\s*[—-]\s*\S", item, re.IGNORECASE):
            external += 1
            continue
        if not match:
            errors.append(f"{location}: invalid blocker entry: {item.strip() or '<empty>'}")
            continue
        blockers.append(f"{int(match.group(1)):02d}")
    if len(blockers) != len(set(blockers)):
        errors.append(f"{location}: duplicate blocker reference")
    return tuple(blockers), external


def load_records(root: Path) -> tuple[list[Record], list[str]]:
    tracker_root = root / ".scratch"
    errors: list[str] = []
    records: list[Record] = []
    if not tracker_root.is_dir():
        return [], [".scratch: tracker directory is missing"]
    candidates = sorted(
        path
        for path in tracker_root.rglob("*.md")
        if inferred_kind(path.relative_to(root)) is not None
    )
    for path in candidates:
        relative = path.relative_to(root)
        expected_kind = inferred_kind(relative)
        assert expected_kind is not None
        text = path.read_text(encoding="utf-8")
        values = metadata_values(text)
        for key in ("type", "status", "blocked by"):
            if len(values.get(key, [])) > 1:
                lines = ", ".join(str(line) for _, line in values[key])
                errors.append(f"{relative}:{lines}: duplicate {key} metadata")
        explicit_type = values.get("type", [(expected_kind, 1)])[0][0].lower()
        if explicit_type != expected_kind:
            errors.append(
                f"{relative}: tracker type {explicit_type!r} does not match path type {expected_kind!r}"
            )
        if "status" not in values or not values["status"][0][0]:
            errors.append(f"{relative}: missing Status metadata")
            continue
        status, status_line = values["status"][0]
        if status not in ALLOWED_STATUSES[expected_kind]:
            errors.append(
                f"{relative}:{status_line}: status {status!r} is invalid for {expected_kind}"
            )
        blockers: tuple[str, ...] = ()
        external_blockers = 0
        if expected_kind == "issue":
            if "blocked by" not in values or not values["blocked by"][0][0]:
                errors.append(f"{relative}: missing Blocked by metadata")
            else:
                raw, line = values["blocked by"][0]
                blockers, external_blockers = parse_blockers(
                    raw, f"{relative}:{line}", errors
                )
        records.append(
            Record(
                path,
                relative,
                expected_kind,
                status,
                text,
                blockers,
                external_blockers,
            )
        )
    return records, errors


def validate(root: Path) -> tuple[list[Record], list[str]]:
    records, errors = load_records(root)
    issues_by_effort: dict[Path, dict[str, Record]] = {}
    for record in records:
        if record.kind != "issue":
            continue
        match = ISSUE_NUMBER.match(record.path.name)
        if not match:
            errors.append(f"{record.relative}: issue filename must start with a two-digit number")
            continue
        effort = record.relative.parent.parent
        number = match.group(1)
        if number in issues_by_effort.setdefault(effort, {}):
            errors.append(f"{record.relative}: duplicate issue number {number}")
        issues_by_effort[effort][number] = record

    graph: dict[Path, tuple[Path, ...]] = {}
    for effort, issues in issues_by_effort.items():
        for number, record in issues.items():
            targets: list[Path] = []
            unresolved: list[str] = []
            for blocker in record.blockers:
                target = issues.get(blocker)
                if target is None:
                    errors.append(f"{record.relative}: blocker {blocker} does not exist in {effort}")
                    continue
                targets.append(target.relative)
                if target.status not in TERMINAL_ISSUE_STATUSES:
                    unresolved.append(blocker)
            if record.external_blockers and record.status not in TERMINAL_ISSUE_STATUSES:
                unresolved.extend(["External"] * record.external_blockers)
            graph[record.relative] = tuple(targets)
            if unresolved and record.status != "blocked":
                errors.append(
                    f"{record.relative}: unresolved blocker(s) {', '.join(unresolved)} require status blocked"
                )
            if record.status == "blocked" and not unresolved:
                errors.append(f"{record.relative}: status blocked has no unresolved blocker")

            if record.status == "resolved":
                answer = ANSWER.search(record.text)
                if answer is None:
                    errors.append(f"{record.relative}: resolved issue is missing Answer")
                elif not VERIFICATION_EVIDENCE.search(record.text[answer.end() :]):
                    errors.append(
                        f"{record.relative}: resolved issue Answer is missing verification evidence"
                    )
                unchecked = UNCHECKED.search(record.text)
                if unchecked is not None:
                    line = record.text.count("\n", 0, unchecked.start()) + 1
                    errors.append(
                        f"{record.relative}:{line}: resolved issue has an unhandled acceptance item"
                    )

    visiting: set[Path] = set()
    visited: set[Path] = set()

    def visit(node: Path, chain: tuple[Path, ...]) -> None:
        if node in visiting:
            start = chain.index(node)
            cycle = " -> ".join(str(item) for item in (*chain[start:], node))
            errors.append(f"{node}: cyclic blocker dependency: {cycle}")
            return
        if node in visited:
            return
        visiting.add(node)
        for target in graph.get(node, ()):
            visit(target, (*chain, node))
        visiting.remove(node)
        visited.add(node)

    for node in sorted(graph):
        visit(node, ())

    for record in records:
        if record.kind != "spec":
            continue
        children = list(issues_by_effort.get(record.relative.parent, {}).values())
        all_terminal = bool(children) and all(
            child.status in TERMINAL_ISSUE_STATUSES for child in children
        )
        if record.status == "resolved" and not all_terminal:
            errors.append(f"{record.relative}: resolved spec has non-terminal or missing child issues")
        elif all_terminal and record.status != "resolved":
            errors.append(f"{record.relative}: all child issues are terminal but spec is not resolved")
    return records, errors


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: inferred from this script)",
    )
    options = parser.parse_args(arguments)
    root = options.root.resolve()
    records, errors = validate(root)
    if errors:
        for error in sorted(set(errors)):
            print(f"tracker consistency: FAIL: {error}", file=sys.stderr)
        return 1
    counts = {kind: sum(record.kind == kind for record in records) for kind in ALLOWED_STATUSES}
    summary = ", ".join(f"{kind}={counts[kind]}" for kind in ALLOWED_STATUSES)
    print(f"tracker consistency: PASS ({summary})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
