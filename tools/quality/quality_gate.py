#!/usr/bin/env python3
"""Repository-owned quality gate orchestration.

The public entry point is quality-gate.sh.  This module also contains small,
deterministic check implementations so the gate can test its own policy.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import urllib.parse


MAX_COMPLETED_RUNS = 20
MAX_FAILURE_LINES = 20
MAX_SUMMARY_BYTES = 8192
DOC_SUFFIXES = {".md"}
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}"),
    re.compile(
        r"(?i)\b(?:password|passwd|token|secret|api[_-]?key)\b\s*[:=]\s*"
        r"['\"]?(?!\$\{|<|REDACTED|CHANGEME|example\b|dummy\b|test\b)"
        r"[A-Za-z0-9._~+/=-]{12,}"
    ),
)
REDACTIONS = (
    (re.compile(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+"), r"\1[REDACTED]"),
    (
        re.compile(
            r"(?i)(\b(?:password|passwd|token|secret|api[_-]?key)\b\s*[:=]\s*)"
            r"([^\s,;]+)"
        ),
        r"\1[REDACTED]",
    ),
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
        ["git", "diff", "--name-status", "-z", "--find-renames", base, head],
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
        if status[:1] in {"A", "M"} and PurePosixPath(paths[-1]).suffix.lower() in DOC_SUFFIXES
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


def added_lines(repo: Path, base: str, head: str) -> list[str]:
    result = git(repo, "diff", "--unified=0", "--no-color", base, head, check=False)
    if result.returncode != 0:
        raise GateError(result.stderr.strip() or "cannot inspect changed content")
    return [line[1:] for line in result.stdout.splitlines() if line.startswith("+") and not line.startswith("+++")]


def check_sensitive_content(repo: Path, base: str, head: str) -> None:
    findings: list[str] = []
    for number, line in enumerate(added_lines(repo, base, head), 1):
        if any(pattern.search(line) for pattern in SECRET_PATTERNS):
            findings.append(f"added line {number}: possible credential or private key")
    if findings:
        raise GateError("\n".join(findings))
    print("checked added content for high-confidence credential patterns")


def redact(text: str) -> str:
    result = text
    for pattern, replacement in REDACTIONS:
        result = pattern.sub(replacement, result)
    return result


class Evidence:
    def __init__(self, repo: Path) -> None:
        self.repo = repo
        self.root = repo / ".quality-gate"
        self.runs = self.root / "runs"
        old_umask = os.umask(0o077)
        try:
            self.runs.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(self.root, 0o700)
            os.chmod(self.runs, 0o700)
            stamp = dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%S.%fZ")
            self.run_id = f"{stamp}-{os.getpid()}"
            self.run_dir = self.runs / self.run_id
            self.run_dir.mkdir(mode=0o700)
            self.log_path = self.run_dir / "full.log"
            self.summary_path = self.run_dir / "summary.txt"
            self.log_path.touch(mode=0o600)
            self._update_latest()
        finally:
            os.umask(old_umask)

    def _update_latest(self) -> None:
        temporary = self.root / f".latest-{os.getpid()}"
        temporary.unlink(missing_ok=True)
        temporary.symlink_to(f"runs/{self.run_id}")
        os.replace(temporary, self.root / "latest")

    def complete(self, summary: str) -> None:
        encoded = redact(summary).encode("utf-8", "replace")[:MAX_SUMMARY_BYTES]
        if len(encoded) == MAX_SUMMARY_BYTES:
            encoded = encoded.rsplit(b"\n", 1)[0] + b"\n[summary truncated]\n"
        self.summary_path.write_bytes(encoded)
        os.chmod(self.summary_path, 0o600)
        marker = self.run_dir / "completed"
        marker.write_text("complete\n", encoding="utf-8")
        os.chmod(marker, 0o600)
        self.prune()

    def prune(self) -> None:
        completed = sorted(
            path for path in self.runs.iterdir() if path.is_dir() and (path / "completed").is_file()
        )
        removable = [path for path in completed if path != self.run_dir]
        while len(completed) > MAX_COMPLETED_RUNS and removable:
            victim = removable.pop(0)
            shutil.rmtree(victim)
            completed.remove(victim)


class Runner:
    def __init__(self, evidence: Evidence, repo: Path) -> None:
        self.evidence = evidence
        self.repo = repo
        self.results: list[tuple[str, int, list[str]]] = []

    def step(self, name: str, command: list[str], env: dict[str, str] | None = None) -> bool:
        with self.evidence.log_path.open("a", encoding="utf-8") as log:
            log.write(f"\n=== {name} ===\n$ {shlex.join(command)}\n")
            log.flush()
            result = subprocess.run(
                command,
                cwd=self.repo,
                env=env,
                text=True,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        tail = self.evidence.log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        excerpt = tail[-MAX_FAILURE_LINES:] if result.returncode else []
        self.results.append((name, result.returncode, excerpt))
        return result.returncode == 0


def extension_commands(repo: Path, groups: tuple[str, ...]) -> list[tuple[str, list[str]]]:
    commands: list[tuple[str, list[str]]] = []
    for group in groups:
        directory = repo / "tools" / "quality" / "checks.d" / group
        if not directory.is_dir():
            continue
        for path in sorted(directory.iterdir()):
            if path.is_file() and os.access(path, os.X_OK):
                commands.append((f"extension:{group}/{path.name}", [str(path)]))
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

    repo = Path(__file__).resolve().parents[2]
    evidence = Evidence(repo)
    base_display = options.base or "UNRESOLVED(missing)"
    head_display = options.head
    profile = "normal"
    reason = "fail-closed before classification"
    runner = Runner(evidence, repo)
    failure: str | None = None
    try:
        base = resolve_commit(repo, options.base or "", "base")
        head = resolve_commit(repo, options.head, "head")
        base_display = base
        head_display = head
        selected, classified_reason, entries = classify(repo, base, head)
        if options.mode == "normal":
            profile = "normal"
            reason = f"caller forced normal; classifier observed {classified_reason}"
        else:
            profile = selected
            reason = classified_reason

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
        core = str(Path(__file__).resolve())
        steps: list[tuple[str, list[str], dict[str, str] | None]] = [
            ("gate-self-tests", [str(repo / "tools/quality/test/run-tests.sh")], environment),
            ("diff-whitespace", ["git", "diff", "--check", base, head], environment),
            (
                "markdown-local-links",
                [sys.executable, core, "_check", "markdown-links", "--repo", str(repo), "--base", base, "--head", head],
                environment,
            ),
            (
                "project-skill-structure",
                [sys.executable, core, "_check", "skill-structure", "--repo", str(repo), "--base", base, "--head", head],
                environment,
            ),
            (
                "sensitive-content",
                [sys.executable, core, "_check", "sensitive", "--repo", str(repo), "--base", base, "--head", head],
                environment,
            ),
        ]
        steps.extend(
            (name, command, environment)
            for name, command in extension_commands(repo, ("common",))
        )
        for name, command, env in steps:
            if not runner.step(name, command, env):
                failure = f"step failed: {name}"
                break
        if failure is None and profile == "normal":
            if not runner.step("maven-clean-verify", ["./mvnw", "-B", "-ntp", "clean", "verify"], environment):
                failure = "step failed: maven-clean-verify"
            else:
                for name, command in extension_commands(repo, ("normal",)):
                    if not runner.step(name, command, environment):
                        failure = f"step failed: {name}"
                        break
        del entries
    except (GateError, OSError) as error:
        failure = str(error)

    status_text = "PASS" if failure is None else "FAIL"
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
    summary = "\n".join(lines) + "\n"
    evidence.complete(summary)
    print(evidence.summary_path.read_text(encoding="utf-8"), end="")
    return 0 if failure is None else 1


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "_check":
        return internal_check(sys.argv[2:])
    return gate(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
