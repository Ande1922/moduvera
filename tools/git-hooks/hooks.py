#!/usr/bin/env python3
"""Install and run the repository-owned pre-push quality gate."""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import stat
import subprocess
import sys


HOOKS_PATH = ".githooks"
HOOK_FILE = ".githooks/pre-push"
MAX_INPUT_BYTES = 1_048_576
MAX_RECORDS = 128
MAX_FIELD_BYTES = 512


class HookError(Exception):
    """A fail-closed condition safe to report without sensitive detail."""


@dataclass(frozen=True)
class PushRecord:
    local_ref: str
    local_oid: str
    remote_ref: str
    remote_oid: str
    deletion: bool


def _git(
    repo: Path,
    arguments: list[str],
    *,
    input_bytes: bytes | None = None,
    timeout: int = 30,
) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            ["git", *arguments],
            cwd=repo,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise HookError("Git operation could not be completed") from error


def _successful_git(repo: Path, arguments: list[str]) -> bytes:
    completed = _git(repo, arguments)
    if completed.returncode != 0:
        raise HookError("repository state could not be resolved")
    return completed.stdout


def _repo_root() -> Path:
    completed = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if completed.returncode != 0:
        raise HookError("command must run inside a Git worktree")
    try:
        root = Path(os.fsdecode(completed.stdout.rstrip(b"\n")))
    except UnicodeError as error:
        raise HookError("repository location is not supported") from error
    if not root.is_absolute():
        raise HookError("repository location could not be resolved")
    return root


def _local_hook_values(repo: Path) -> list[str]:
    completed = _git(repo, ["config", "--local", "--null", "--get-all", "core.hooksPath"])
    if completed.returncode == 1:
        return []
    if completed.returncode != 0:
        raise HookError("local hook configuration could not be read")
    try:
        return [value.decode("utf-8") for value in completed.stdout.split(b"\0") if value]
    except UnicodeDecodeError as error:
        raise HookError("local hook configuration is not supported") from error


def _effective_hook_values(repo: Path) -> list[str]:
    completed = _git(repo, ["config", "--null", "--get-all", "core.hooksPath"])
    if completed.returncode == 1:
        return []
    if completed.returncode != 0:
        raise HookError("hook configuration could not be read")
    try:
        return [value.decode("utf-8") for value in completed.stdout.split(b"\0") if value]
    except UnicodeDecodeError as error:
        raise HookError("hook configuration is not supported") from error


def _validate_install_asset(repo: Path) -> None:
    hook = repo / HOOK_FILE
    try:
        metadata = hook.lstat()
    except OSError as error:
        raise HookError("repository pre-push hook is unavailable") from error
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o111 == 0:
        raise HookError("repository pre-push hook is not a regular executable")
    index = _git(repo, ["ls-files", "--stage", "--", HOOK_FILE])
    if index.returncode != 0:
        raise HookError("repository pre-push hook tracking could not be verified")
    entries = [line for line in index.stdout.splitlines() if line]
    if len(entries) != 1 or not entries[0].startswith(b"100755 "):
        raise HookError("repository pre-push hook must be tracked with executable mode")
    tree = _git(repo, ["ls-tree", "HEAD", "--", HOOK_FILE])
    tree_entries = [line for line in tree.stdout.splitlines() if line]
    if tree.returncode != 0 or len(tree_entries) != 1 or not tree_entries[0].startswith(b"100755 blob "):
        raise HookError("repository pre-push hook must be committed with executable mode")


def install(repo: Path) -> None:
    _validate_install_asset(repo)
    local = _local_hook_values(repo)
    if local == [HOOKS_PATH]:
        print("pre-push hook is already enabled for this repository")
        return
    if local:
        raise HookError("local core.hooksPath is already configured; no changes made")
    if _effective_hook_values(repo):
        raise HookError("a non-local core.hooksPath is active; no changes made")
    configured = _git(repo, ["config", "--local", "core.hooksPath", HOOKS_PATH])
    if configured.returncode != 0:
        raise HookError("local hook configuration could not be written")
    print("pre-push hook enabled for this repository")


def uninstall(repo: Path) -> None:
    local = _local_hook_values(repo)
    if not local:
        print("repository pre-push hook is already disabled")
        return
    if local != [HOOKS_PATH]:
        raise HookError("local core.hooksPath is not owned by this repository; no changes made")
    removed = _git(repo, ["config", "--local", "--unset-all", "core.hooksPath"])
    if removed.returncode != 0:
        raise HookError("local hook configuration could not be removed")
    print("pre-push hook disabled for this repository")


def _object_format(repo: Path) -> tuple[int, str]:
    value = _successful_git(repo, ["rev-parse", "--show-object-format"]).strip()
    if value == b"sha1":
        return 40, "0" * 40
    if value == b"sha256":
        return 64, "0" * 64
    raise HookError("repository object format is not supported")


def _valid_oid(value: str, oid_length: int) -> bool:
    return len(value) == oid_length and all(character in "0123456789abcdef" for character in value)


def _valid_ref(repo: Path, value: str) -> bool:
    if not value.startswith("refs/"):
        return False
    return _git(repo, ["check-ref-format", value]).returncode == 0


def _parse_records(repo: Path, data: bytes, oid_length: int, zero_oid: str) -> list[PushRecord]:
    if not data or len(data) > MAX_INPUT_BYTES or b"\0" in data:
        raise HookError("pre-push input is empty or exceeds its safety limit")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise HookError("pre-push input encoding is invalid") from error
    lines = text.splitlines()
    if not lines or len(lines) > MAX_RECORDS:
        raise HookError("pre-push input record count is invalid")
    records: list[PushRecord] = []
    local_refs: set[str] = set()
    remote_refs: set[str] = set()
    for line in lines:
        fields = line.split()
        if len(fields) != 4 or any(len(field.encode("utf-8")) > MAX_FIELD_BYTES for field in fields):
            raise HookError("pre-push input contains a malformed record")
        local_ref, local_oid, remote_ref, remote_oid = fields
        if not _valid_ref(repo, remote_ref) or not _valid_oid(remote_oid, oid_length):
            raise HookError("pre-push input contains an invalid remote identity")
        deletion = local_ref == "(delete)"
        if deletion:
            if local_oid != zero_oid or remote_oid == zero_oid:
                raise HookError("pre-push deletion record is inconsistent")
        elif (
            not _valid_ref(repo, local_ref)
            or not _valid_oid(local_oid, oid_length)
            or local_oid == zero_oid
        ):
            raise HookError("pre-push input contains an invalid local identity")
        if local_ref in local_refs or remote_ref in remote_refs:
            raise HookError("pre-push input contains duplicate refs")
        local_refs.add(local_ref)
        remote_refs.add(remote_ref)
        records.append(PushRecord(local_ref, local_oid, remote_ref, remote_oid, deletion))
    return records


def _head(repo: Path) -> str:
    value = _successful_git(repo, ["rev-parse", "--verify", "HEAD^{commit}"]).strip()
    try:
        return value.decode("ascii")
    except UnicodeDecodeError as error:
        raise HookError("checkout HEAD could not be resolved") from error


def _require_commit(repo: Path, oid: str) -> None:
    kind = _git(repo, ["cat-file", "-t", oid])
    if kind.returncode != 0 or kind.stdout.strip() != b"commit":
        raise HookError("a required remote commit is unavailable locally")


def _is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    completed = _git(repo, ["merge-base", "--is-ancestor", ancestor, descendant])
    if completed.returncode not in (0, 1):
        raise HookError("commit ancestry could not be verified")
    return completed.returncode == 0


def _remote_default(repo: Path, remote_location: str, oid_length: int) -> tuple[str, str]:
    completed = _git(
        repo,
        ["ls-remote", "--symref", "--", remote_location, "HEAD"],
        timeout=30,
    )
    if completed.returncode != 0:
        raise HookError("remote default branch could not be resolved")
    symbolic: list[str] = []
    oids: list[str] = []
    try:
        lines = completed.stdout.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise HookError("remote default branch response is invalid") from error
    for line in lines:
        fields = line.split("\t")
        if len(fields) != 2 or fields[1] != "HEAD":
            raise HookError("remote default branch response is invalid")
        if fields[0].startswith("ref: "):
            symbolic.append(fields[0][5:])
        else:
            oids.append(fields[0])
    if len(symbolic) != 1 or len(oids) != 1:
        raise HookError("remote default branch is missing or ambiguous")
    if not _valid_ref(repo, symbolic[0]) or not _valid_oid(oids[0], oid_length):
        raise HookError("remote default branch identity is invalid")
    _require_commit(repo, oids[0])
    return symbolic[0], oids[0]


def _unique_merge_base(repo: Path, commits: list[str], label: str) -> str:
    if len(commits) == 1:
        return commits[0]
    arguments = ["merge-base", "--all"]
    if len(commits) > 2:
        arguments.append("--octopus")
    completed = _git(repo, [*arguments, *commits])
    bases = [line.decode("ascii") for line in completed.stdout.splitlines() if line]
    if completed.returncode != 0 or len(bases) != 1:
        raise HookError(f"{label} is missing or ambiguous")
    _require_commit(repo, bases[0])
    return bases[0]


def _resolve_base(
    repo: Path,
    records: list[PushRecord],
    remote_location: str,
    head: str,
    oid_length: int,
    zero_oid: str,
) -> str:
    non_deletes = [record for record in records if not record.deletion]
    if not non_deletes:
        raise HookError("deletion-only pushes cannot produce quality-gate evidence")
    if any(record.local_oid != head for record in non_deletes):
        raise HookError("every pushed commit must equal the checkout HEAD")
    for record in records:
        if record.remote_oid != zero_oid:
            _require_commit(repo, record.remote_oid)
    default_oid: str | None = None
    bases: list[str] = []
    for record in non_deletes:
        if record.remote_oid == zero_oid:
            if default_oid is None:
                _default_ref, default_oid = _remote_default(repo, remote_location, oid_length)
            base = _unique_merge_base(repo, [head, default_oid], "new-branch merge-base")
        else:
            base = record.remote_oid
        if not _is_ancestor(repo, base, head):
            raise HookError("a pushed ref is not based on its advertised remote commit")
        bases.append(base)
    unique_bases = list(dict.fromkeys(bases))
    aggregate = _unique_merge_base(repo, unique_bases, "aggregate comparison base")
    if not all(_is_ancestor(repo, aggregate, base) for base in unique_bases):
        raise HookError("aggregate comparison base is not conservative")
    return aggregate


def _ensure_clean(repo: Path) -> None:
    if _git(repo, ["diff", "--quiet", "--cached", "--"]).returncode != 0:
        raise HookError("checkout is not clean; formal pre-push evidence was not started")
    if _git(repo, ["diff", "--quiet", "--"]).returncode != 0:
        raise HookError("checkout is not clean; formal pre-push evidence was not started")
    untracked = _git(repo, ["ls-files", "--others", "--exclude-standard", "-z"])
    if untracked.returncode != 0 or untracked.stdout:
        raise HookError("checkout is not clean; formal pre-push evidence was not started")
    flags = _git(repo, ["ls-files", "-v", "-z"])
    if flags.returncode != 0:
        raise HookError("checkout index state could not be verified")
    for entry in flags.stdout.split(b"\0"):
        if entry and (entry[:1] == b"S" or entry[:1].islower()):
            raise HookError("checkout contains hidden index flags; formal evidence was not started")


def _gate_ref(records: list[PushRecord]) -> str:
    refs = ",".join(record.remote_ref for record in records)
    value = f"pre-push:{refs}"
    return value if len(value.encode("utf-8")) <= 2048 else f"pre-push:{len(records)}-refs"


def pre_push(repo: Path, arguments: list[str]) -> None:
    if len(arguments) != 2:
        raise HookError("pre-push requires the Git-provided remote arguments")
    _remote_name, remote_location = arguments
    oid_length, zero_oid = _object_format(repo)
    data = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
    records = _parse_records(repo, data, oid_length, zero_oid)
    head = _head(repo)
    base = _resolve_base(repo, records, remote_location, head, oid_length, zero_oid)
    _ensure_clean(repo)
    gate = repo / "tools/quality/quality-gate.sh"
    try:
        metadata = gate.lstat()
    except OSError as error:
        raise HookError("repository quality gate is unavailable") from error
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o111 == 0:
        raise HookError("repository quality gate is not a regular executable")
    os.execv(
        gate,
        [
            str(gate),
            "auto",
            "--base",
            base,
            "--head",
            head,
            "--ref",
            _gate_ref(records),
        ],
    )


def main(arguments: list[str]) -> int:
    try:
        if not arguments:
            raise HookError("usage: hooks.py {install|uninstall|pre-push}")
        repo = _repo_root()
        action, rest = arguments[0], arguments[1:]
        if action == "install" and not rest:
            install(repo)
        elif action == "uninstall" and not rest:
            uninstall(repo)
        elif action == "pre-push":
            pre_push(repo, rest)
        else:
            raise HookError("usage: hooks.py {install|uninstall|pre-push}")
    except HookError as error:
        print(f"pre-push gate: {error}", file=sys.stderr)
        return 1
    except OSError:
        print("pre-push gate: operating-system operation failed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
