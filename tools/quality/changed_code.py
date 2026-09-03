#!/usr/bin/env python3
"""Report changed-line coverage and changed-method CRAP from a Normal gate build."""

from __future__ import annotations

import argparse
from collections import Counter
import contextlib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
from typing import Any, NamedTuple
import xml.etree.ElementTree as ET


MAVEN_COMMAND = ["./mvnw", "-B", "-ntp", "clean", "verify"]
MAX_EXTENSION_SUMMARY_BYTES = 4096
PRODUCTION_MARKER = "/src/main/java/"
HUNK_HEADER = re.compile(
    r"^@@ -\d+(?:,\d+)? \+(?P<start>\d+)(?:,(?P<count>\d+))? @@"
)
JAVAP_LINE = re.compile(r"^\s+line (?P<line>\d+): \d+$")


class ChangedCodeError(RuntimeError):
    """A deterministic changed-code evidence failure."""


class Change(NamedTuple):
    status: str
    old_path: str | None
    path: str
    added_lines: tuple[int, ...]


class Exclusion(NamedTuple):
    path: str
    line: int | None
    reason: str


class LineScore(NamedTuple):
    path: str
    line: int
    covered: bool
    missed_instructions: int
    covered_instructions: int


class MethodScore(NamedTuple):
    path: str
    class_name: str
    name: str
    descriptor: str
    changed_lines: tuple[int, ...]
    line_coverage: float
    complexity: int
    crap: float


class Artifact(NamedTuple):
    path: str
    sha256: str
    mtime_ns: int


class AnalysisResult(NamedTuple):
    base: str
    head: str
    provenance: Artifact
    lines: tuple[LineScore, ...]
    methods: tuple[MethodScore, ...]
    exclusions: tuple[Exclusion, ...]
    artifacts: tuple[Artifact, ...]

    @property
    def scoreable_lines(self) -> int:
        return len(self.lines)

    @property
    def covered_lines(self) -> int:
        return sum(line.covered for line in self.lines)


class MethodCoverage(NamedTuple):
    class_name: str
    source_key: tuple[str, str]
    name: str
    descriptor: str
    missed_lines: int
    covered_lines: int
    complexity: int


class JacocoReport(NamedTuple):
    source_lines: dict[tuple[str, str], dict[int, tuple[int, int]]]
    methods: dict[tuple[str, str, str], MethodCoverage]
    source_classes: dict[tuple[str, str], tuple[str, ...]]


def _run(
    command: list[str], cwd: Path, *, check: bool = True
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        diagnostic = result.stderr.strip() or result.stdout.strip() or "command failed"
        raise ChangedCodeError(diagnostic)
    return result


def _resolve(repo: Path, revision: str, label: str) -> str:
    result = _run(
        ["git", "rev-parse", "--verify", f"{revision}^{{commit}}"],
        repo,
        check=False,
    )
    if result.returncode != 0:
        raise ChangedCodeError(f"cannot resolve {label}: {revision}")
    return result.stdout.strip()


def _require_clean_head(repo: Path, head: str) -> None:
    current = _resolve(repo, "HEAD", "checkout HEAD")
    if current != head:
        raise ChangedCodeError(
            f"checkout HEAD mismatch: expected {head}, found {current}"
        )
    status_result = _run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        repo,
        check=False,
    )
    if status_result.returncode != 0:
        raise ChangedCodeError(status_result.stderr.strip() or "cannot inspect checkout")
    if status_result.stdout:
        paths = status_result.stdout.splitlines()
        raise ChangedCodeError(
            "changed-code checkout is not clean: " + ", ".join(paths[:10])
        )


def _parse_name_status(repo: Path, base: str, head: str) -> list[tuple[str, tuple[str, ...]]]:
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
        raise ChangedCodeError(
            result.stderr.decode("utf-8", "replace").strip() or "git diff failed"
        )
    fields = result.stdout.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    entries: list[tuple[str, tuple[str, ...]]] = []
    index = 0
    while index < len(fields):
        status_text = fields[index].decode("utf-8", "surrogateescape")
        index += 1
        count = 2 if status_text[:1] in {"R", "C"} else 1
        if index + count > len(fields):
            raise ChangedCodeError("cannot parse changed paths")
        paths = tuple(
            field.decode("utf-8", "surrogateescape")
            for field in fields[index : index + count]
        )
        index += count
        entries.append((status_text, paths))
    return entries


def _added_lines(repo: Path, base: str, head: str, path: str) -> tuple[int, ...]:
    result = _run(
        [
            "git",
            "diff",
            "--unified=0",
            "--no-color",
            "--no-ext-diff",
            "--find-renames",
            "--find-copies-harder",
            base,
            head,
            "--",
            path,
        ],
        repo,
        check=False,
    )
    if result.returncode != 0:
        raise ChangedCodeError(result.stderr.strip() or f"cannot diff {path}")
    added: list[int] = []
    new_line: int | None = None
    for line in result.stdout.splitlines():
        header = HUNK_HEADER.match(line)
        if header:
            new_line = int(header.group("start"))
        elif new_line is None or line.startswith(("diff ", "index ", "---", "+++")):
            continue
        elif line.startswith("+") and not line.startswith("+++"):
            added.append(new_line)
            new_line += 1
        elif line.startswith("-") and not line.startswith("---"):
            continue
        elif not line.startswith("\\"):
            new_line += 1
    return tuple(sorted(set(added)))


def changed_files(repo: Path, base: str, head: str) -> tuple[Change, ...]:
    changes: list[Change] = []
    for status, paths in _parse_name_status(repo, base, head):
        kind = status[:1]
        old_path = paths[0] if kind in {"R", "C"} else None
        path = paths[-1]
        lines = () if kind == "D" else _added_lines(repo, base, head, path)
        changes.append(Change(status, old_path, path, lines))
    return tuple(changes)


def _safe_regular(path: Path, root: Path, label: str) -> os.stat_result:
    try:
        state = path.lstat()
    except FileNotFoundError as error:
        raise ChangedCodeError(f"missing {label}: {path}") from error
    if stat.S_ISLNK(state.st_mode) or not stat.S_ISREG(state.st_mode):
        raise ChangedCodeError(f"{label} must be a regular non-symlink file: {path}")
    if not path.resolve(strict=True).is_relative_to(root.resolve(strict=True)):
        raise ChangedCodeError(f"{label} escapes expected root: {path}")
    return state


def _read_json_regular(path: Path) -> dict[str, Any]:
    state = _safe_regular(path, path.parent, "Maven provenance")
    if state.st_size > 16 * 1024:
        raise ChangedCodeError("Maven provenance is unexpectedly large")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ChangedCodeError("invalid Maven provenance JSON") from error
    if not isinstance(payload, dict):
        raise ChangedCodeError("invalid Maven provenance object")
    return payload


def _validate_provenance(
    path: Path, base: str, head: str
) -> tuple[int, int, dict[str, Any]]:
    payload = _read_json_regular(path)
    if payload.get("base") != base:
        raise ChangedCodeError("provenance base mismatch")
    if payload.get("head") != head:
        raise ChangedCodeError("provenance head mismatch")
    if payload.get("command") != MAVEN_COMMAND or payload.get("status") != "PASS":
        raise ChangedCodeError("provenance does not record successful exact clean verify")
    started = payload.get("started_ns")
    completed = payload.get("completed_ns")
    if (
        not isinstance(started, int)
        or isinstance(started, bool)
        or not isinstance(completed, int)
        or isinstance(completed, bool)
        or started <= 0
        or completed < started
    ):
        raise ChangedCodeError("invalid Maven provenance time window")
    return started, completed, payload


def _artifact(
    path: Path,
    repo: Path,
    label: str,
    started_ns: int,
    completed_ns: int,
) -> Artifact:
    state = _safe_regular(path, repo, label)
    if not started_ns <= state.st_mtime_ns <= completed_ns:
        raise ChangedCodeError(f"{label} is outside Maven build window: {path}")
    relative = path.relative_to(repo).as_posix()
    return Artifact(relative, hashlib.sha256(path.read_bytes()).hexdigest(), state.st_mtime_ns)


def _source_artifact(path: Path, repo: Path) -> Artifact:
    state = _safe_regular(path, repo, "changed production source")
    relative = path.relative_to(repo).as_posix()
    return Artifact(
        relative,
        hashlib.sha256(path.read_bytes()).hexdigest(),
        state.st_mtime_ns,
    )


def _provenance_artifact(path: Path) -> Artifact:
    state = _safe_regular(path, path.parent, "Maven provenance")
    return Artifact(
        path.name,
        hashlib.sha256(path.read_bytes()).hexdigest(),
        state.st_mtime_ns,
    )


def _counter(element: ET.Element, counter_type: str) -> tuple[int, int]:
    matches = [
        child for child in element.findall("counter") if child.get("type") == counter_type
    ]
    if len(matches) != 1:
        raise ChangedCodeError(f"missing or duplicate JaCoCo {counter_type} counter")
    try:
        missed = int(matches[0].attrib["missed"])
        covered = int(matches[0].attrib["covered"])
    except (KeyError, ValueError) as error:
        raise ChangedCodeError(f"invalid JaCoCo {counter_type} counter") from error
    if missed < 0 or covered < 0:
        raise ChangedCodeError(f"negative JaCoCo {counter_type} counter")
    return missed, covered


def load_jacoco(path: Path) -> JacocoReport:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise ChangedCodeError(f"cannot parse JaCoCo report: {path}") from error
    if root.tag != "report":
        raise ChangedCodeError(f"unexpected JaCoCo root element: {root.tag}")
    source_lines: dict[tuple[str, str], dict[int, tuple[int, int]]] = {}
    methods: dict[tuple[str, str, str], MethodCoverage] = {}
    source_classes: dict[tuple[str, str], list[str]] = {}
    for package in root.findall("package"):
        package_name = package.get("name")
        if package_name is None:
            raise ChangedCodeError("JaCoCo package is missing name")
        for source in package.findall("sourcefile"):
            source_name = source.get("name")
            if source_name is None:
                raise ChangedCodeError("JaCoCo sourcefile is missing name")
            source_key = (package_name, source_name)
            if source_key in source_lines:
                raise ChangedCodeError(f"duplicate JaCoCo sourcefile: {source_key}")
            line_map: dict[int, tuple[int, int]] = {}
            for line in source.findall("line"):
                try:
                    number = int(line.attrib["nr"])
                    missed = int(line.attrib["mi"])
                    covered = int(line.attrib["ci"])
                except (KeyError, ValueError) as error:
                    raise ChangedCodeError("invalid JaCoCo source line") from error
                if number <= 0 or missed < 0 or covered < 0 or number in line_map:
                    raise ChangedCodeError("invalid or duplicate JaCoCo source line")
                if missed + covered <= 0:
                    raise ChangedCodeError("JaCoCo executable line has no instructions")
                line_map[number] = (missed, covered)
            source_lines[source_key] = line_map
        for class_element in package.findall("class"):
            class_name = class_element.get("name")
            source_name = class_element.get("sourcefilename")
            if class_name is None or source_name is None:
                raise ChangedCodeError("JaCoCo class is missing identity")
            source_key = (package_name, source_name)
            source_classes.setdefault(source_key, []).append(class_name)
            for method in class_element.findall("method"):
                name = method.get("name")
                descriptor = method.get("desc")
                if name is None or descriptor is None:
                    raise ChangedCodeError("JaCoCo method is missing identity")
                key = (class_name, name, descriptor)
                if key in methods:
                    raise ChangedCodeError(f"duplicate JaCoCo method: {key}")
                missed_lines, covered_lines = _counter(method, "LINE")
                missed_complexity, covered_complexity = _counter(method, "COMPLEXITY")
                complexity = missed_complexity + covered_complexity
                if complexity <= 0:
                    raise ChangedCodeError(f"unscorable JaCoCo method complexity: {key}")
                methods[key] = MethodCoverage(
                    class_name,
                    source_key,
                    name,
                    descriptor,
                    missed_lines,
                    covered_lines,
                    complexity,
                )
    return JacocoReport(
        source_lines,
        methods,
        {key: tuple(sorted(values)) for key, values in source_classes.items()},
    )


def _method_name(header: str, class_name: str) -> str | None:
    if header == "static {};":
        return "<clinit>"
    if "(" not in header:
        return None
    token = header.split("(", 1)[0].split()[-1]
    dotted_class = class_name.replace("/", ".")
    if token == dotted_class or token.endswith("." + dotted_class.rsplit(".", 1)[-1]):
        return "<init>"
    return token


def javap_line_tables(
    repo: Path,
    classes: Path,
    class_name: str,
    started_ns: int,
    completed_ns: int,
) -> tuple[dict[tuple[str, str, str], set[int]], Artifact]:
    class_file = classes / f"{class_name}.class"
    artifact = _artifact(class_file, repo, "compiled class", started_ns, completed_ns)
    environment = os.environ.copy()
    environment.update({"LC_ALL": "C", "LANG": "C"})
    result = subprocess.run(
        [
            "javap",
            "-p",
            "-c",
            "-s",
            "-l",
            "-classpath",
            str(classes),
            class_name.replace("/", "."),
        ],
        cwd=repo,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise ChangedCodeError(
            result.stderr.strip() or f"javap failed for {class_name}"
        )
    tables: dict[tuple[str, str, str], set[int]] = {}
    current_name: str | None = None
    current_descriptor: str | None = None
    current_lines: set[int] = set()
    in_line_table = False

    def finish() -> None:
        nonlocal current_name, current_descriptor, current_lines, in_line_table
        if current_name is not None and current_descriptor is not None:
            key = (class_name, current_name, current_descriptor)
            if key in tables:
                raise ChangedCodeError(f"duplicate javap method: {key}")
            tables[key] = set(current_lines)
        current_name = None
        current_descriptor = None
        current_lines = set()
        in_line_table = False

    for raw_line in result.stdout.splitlines():
        if raw_line.startswith("  ") and not raw_line.startswith("    "):
            stripped = raw_line.strip()
            if stripped.endswith(";"):
                finish()
                current_name = _method_name(stripped, class_name)
                continue
        stripped = raw_line.strip()
        if current_name is not None and stripped.startswith("descriptor:"):
            current_descriptor = stripped.split(":", 1)[1].strip()
            continue
        if current_name is not None and stripped == "LineNumberTable:":
            in_line_table = True
            continue
        if in_line_table:
            match = JAVAP_LINE.match(raw_line)
            if match:
                current_lines.add(int(match.group("line")))
            elif stripped:
                in_line_table = False
    finish()
    return tables, artifact


def _source_identity(path: str) -> tuple[str, str, str]:
    module_text, source_text = path.split(PRODUCTION_MARKER, 1)
    source_path = PurePosixPath(source_text)
    package = source_path.parent.as_posix()
    if package == ".":
        package = ""
    return module_text, package, source_path.name


def _exclusion_reason(path: str) -> str:
    suffix = PurePosixPath(path).suffix.lower()
    name = PurePosixPath(path).name
    if suffix in {".md", ".adoc", ".pom", ".xml"} or name in {
        "pom.xml",
        "Dockerfile",
        ".dockerignore",
    }:
        return "documentation or build change"
    if path.endswith(".java"):
        return "non-production Java source"
    return "outside production Java scope"


def calculate_crap(complexity: int, line_coverage: float) -> float:
    return complexity * complexity * (1.0 - line_coverage) ** 3 + complexity


def analyze(
    repo: Path,
    base_revision: str,
    head_revision: str,
    provenance_path: Path,
) -> AnalysisResult:
    repo = repo.resolve(strict=True)
    base = _resolve(repo, base_revision, "base")
    head = _resolve(repo, head_revision, "head")
    _require_clean_head(repo, head)
    started_ns, completed_ns, _provenance = _validate_provenance(
        provenance_path, base, head
    )
    provenance_artifact = _provenance_artifact(provenance_path)

    lines: list[LineScore] = []
    exclusions: list[Exclusion] = []
    changed_method_lines: dict[tuple[str, str, str, str], set[int]] = {}
    method_coverage: dict[tuple[str, str, str, str], MethodCoverage] = {}
    method_path: dict[tuple[str, str, str, str], str] = {}
    artifacts: dict[str, Artifact] = {}
    reports: dict[str, JacocoReport] = {}
    javap_cache: dict[tuple[str, str], dict[tuple[str, str, str], set[int]]] = {}

    for change in changed_files(repo, base, head):
        kind = change.status[:1]
        if kind == "D" and PRODUCTION_MARKER in "/" + change.path:
            exclusions.append(Exclusion(change.path, None, "deleted production Java source"))
            continue
        normalized = "/" + change.path
        if PRODUCTION_MARKER not in normalized or not change.path.endswith(".java"):
            exclusions.append(Exclusion(change.path, None, _exclusion_reason(change.path)))
            continue
        if not change.added_lines:
            exclusions.append(Exclusion(change.path, None, "no changed head-side lines"))
            continue

        module_text, package, source_name = _source_identity(change.path)
        module = repo / module_text
        if not (module / "pom.xml").is_file():
            raise ChangedCodeError(f"cannot locate Maven module for {change.path}")
        source_path = repo / change.path
        artifacts[change.path] = _source_artifact(source_path, repo)
        report_path = module / "target/site/jacoco/jacoco.xml"
        report_key = report_path.relative_to(repo).as_posix()
        if report_key not in reports:
            artifacts[report_key] = _artifact(
                report_path, repo, "JaCoCo report", started_ns, completed_ns
            )
            reports[report_key] = load_jacoco(report_path)
        report = reports[report_key]
        source_key = (package, source_name)
        if source_key not in report.source_lines:
            raise ChangedCodeError(
                f"changed production source is absent from JaCoCo report: {change.path}"
            )
        source_line_map = report.source_lines[source_key]
        for line_number in change.added_lines:
            counters = source_line_map.get(line_number)
            if counters is None:
                exclusions.append(
                    Exclusion(
                        change.path,
                        line_number,
                        "non-executable production Java line",
                    )
                )
                continue
            missed, covered = counters
            lines.append(
                LineScore(change.path, line_number, covered > 0, missed, covered)
            )
            owners: list[tuple[str, str, str, str]] = []
            for class_name in report.source_classes.get(source_key, ()):
                cache_key = (report_key, class_name)
                if cache_key not in javap_cache:
                    tables, class_artifact = javap_line_tables(
                        repo,
                        module / "target/classes",
                        class_name,
                        started_ns,
                        completed_ns,
                    )
                    javap_cache[cache_key] = tables
                    artifacts[class_artifact.path] = class_artifact
                for method_key, owned_lines in javap_cache[cache_key].items():
                    if line_number in owned_lines and method_key in report.methods:
                        owners.append((report_key, *method_key))
            if not owners:
                raise ChangedCodeError(
                    f"cannot map changed executable line to scored method: "
                    f"{change.path}:{line_number}"
                )
            for owner in owners:
                changed_method_lines.setdefault(owner, set()).add(line_number)
                method_coverage[owner] = report.methods[owner[1:]]
                method_path[owner] = change.path

    methods: list[MethodScore] = []
    for key in sorted(changed_method_lines):
        coverage = method_coverage[key]
        total_lines = coverage.missed_lines + coverage.covered_lines
        if total_lines <= 0:
            raise ChangedCodeError(f"changed method has no scoreable JaCoCo lines: {key}")
        line_coverage = coverage.covered_lines / total_lines
        methods.append(
            MethodScore(
                method_path[key],
                coverage.class_name,
                coverage.name,
                coverage.descriptor,
                tuple(sorted(changed_method_lines[key])),
                line_coverage,
                coverage.complexity,
                calculate_crap(coverage.complexity, line_coverage),
            )
        )

    return AnalysisResult(
        base,
        head,
        provenance_artifact,
        tuple(sorted(lines)),
        tuple(methods),
        tuple(sorted(exclusions)),
        tuple(sorted(artifacts.values())),
    )


def render_summary(result: AnalysisResult) -> str:
    file_count = len({line.path for line in result.lines})
    output = [
        f"scoreable scope: {result.scoreable_lines} changed executable line(s), "
        f"{len(result.methods)} changed method(s) in {file_count} production file(s)",
    ]
    if result.scoreable_lines:
        percentage = result.covered_lines * 100.0 / result.scoreable_lines
        output.append(
            f"changed-line coverage: {percentage:.2f}% "
            f"({result.covered_lines}/{result.scoreable_lines})"
        )
    else:
        output.append("changed-line coverage: n/a (0 scoreable lines)")
    if result.methods:
        highest = max(result.methods, key=lambda method: method.crap)
        output.append(
            f"highest changed-method CRAP: {highest.crap:.3f} "
            f"({highest.class_name.replace('/', '.')}.{highest.name}{highest.descriptor})"
        )
    else:
        output.append("highest changed-method CRAP: n/a (0 changed methods)")
    reasons = Counter(item.reason for item in result.exclusions)
    if reasons:
        rendered = "; ".join(
            f"{reason}={count}" for reason, count in sorted(reasons.items())
        )
        output.append(f"exclusions: {len(result.exclusions)} ({rendered})")
    else:
        output.append("exclusions: 0")
    output.append("numeric policy: report-only; completeness and scorability are blocking")
    summary = "\n".join(output) + "\n"
    if len(summary.encode("utf-8")) > MAX_EXTENSION_SUMMARY_BYTES:
        raise ChangedCodeError("changed-code summary exceeds bounded size")
    return summary


def _json_data(result: AnalysisResult) -> dict[str, Any]:
    return {
        "schema": 1,
        "base": result.base,
        "head": result.head,
        "mavenProvenance": result.provenance._asdict(),
        "numericPolicy": "report-only",
        "changedLineCoverage": {
            "covered": result.covered_lines,
            "scoreable": result.scoreable_lines,
            "ratio": (
                result.covered_lines / result.scoreable_lines
                if result.scoreable_lines
                else None
            ),
        },
        "lines": [line._asdict() for line in result.lines],
        "methods": [method._asdict() for method in result.methods],
        "exclusions": [item._asdict() for item in result.exclusions],
        "artifacts": [item._asdict() for item in result.artifacts],
    }


def _write_private(path: Path, parent: Path, content: str) -> None:
    if path.parent != parent:
        raise ChangedCodeError(f"unsafe evidence output parent: {path}")
    parent_state = parent.lstat()
    if stat.S_ISLNK(parent_state.st_mode) or not stat.S_ISDIR(parent_state.st_mode):
        raise ChangedCodeError(f"evidence output directory is unsafe: {parent}")
    parent_descriptor = os.open(
        parent,
        os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        descriptor = os.open(
            path.name,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
            dir_fd=parent_descriptor,
        )
    except FileExistsError as error:
        raise ChangedCodeError(f"evidence output already exists: {path}") from error
    finally:
        os.close(parent_descriptor)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        with contextlib.suppress(OSError):
            os.close(descriptor)
        raise


def write_evidence(
    result: AnalysisResult, run_dir: Path, details_path: Path, summary_path: Path
) -> str:
    run_dir = run_dir.resolve(strict=True)
    if details_path.parent.resolve(strict=True) != run_dir:
        raise ChangedCodeError(f"unsafe evidence output parent: {details_path}")
    if summary_path.parent.resolve(strict=True) != run_dir:
        raise ChangedCodeError(f"unsafe evidence output parent: {summary_path}")
    details_path = run_dir / details_path.name
    summary_path = run_dir / summary_path.name
    summary = render_summary(result)
    _write_private(
        details_path,
        run_dir,
        json.dumps(_json_data(result), indent=2, sort_keys=True) + "\n",
    )
    _write_private(summary_path, run_dir, summary)
    return summary


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--provenance", required=True)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--details", required=True)
    parser.add_argument("--summary", required=True)
    options = parser.parse_args(arguments)
    try:
        run_dir = Path(options.run_dir)
        result = analyze(
            Path(options.repo),
            options.base,
            options.head,
            Path(options.provenance),
        )
        summary = write_evidence(
            result,
            run_dir,
            Path(options.details),
            Path(options.summary),
        )
        print(summary, end="")
    except (ChangedCodeError, OSError) as error:
        print(f"changed-code evidence failure: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
