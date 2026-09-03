#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import tempfile
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[3]


def run(
    arguments: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    input_text: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        arguments,
        cwd=cwd,
        env=env,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=15,
        check=False,
    )
    if check and completed.returncode != 0:
        raise AssertionError(
            f"command failed ({completed.returncode}): {arguments!r}\n"
            f"stdout={completed.stdout!r}\nstderr={completed.stderr!r}"
        )
    return completed


class HookRepository:
    def __init__(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="git-hook-test-")
        self.root = Path(self.temp.name) / "checkout"
        self.remote = Path(self.temp.name) / "remote.git"
        self.home = Path(self.temp.name) / "home"
        self.gate_log = Path(self.temp.name) / "gate.log"
        self.home.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            {
                "HOME": str(self.home),
                "GIT_CONFIG_NOSYSTEM": "1",
                "GATE_LOG": str(self.gate_log),
            }
        )
        self.root.mkdir()
        run(["git", "init", "--initial-branch=main"], cwd=self.root, env=self.env)
        run(["git", "config", "user.name", "Hook Test"], cwd=self.root, env=self.env)
        run(
            ["git", "config", "user.email", "hook-test@example.invalid"],
            cwd=self.root,
            env=self.env,
        )
        (self.root / "README.md").write_text("fixture\n", encoding="utf-8")
        run(["git", "add", "README.md"], cwd=self.root, env=self.env)
        run(["git", "commit", "-m", "initial"], cwd=self.root, env=self.env)
        self.remote_base = self.rev("HEAD")
        run(["git", "init", "--bare", "--initial-branch=main", str(self.remote)], cwd=self.root, env=self.env)
        run(["git", "remote", "add", "origin", str(self.remote)], cwd=self.root, env=self.env)
        run(["git", "push", "origin", "main"], cwd=self.root, env=self.env)
        self._copy_project_files()
        run(["git", "add", ".githooks", "tools"], cwd=self.root, env=self.env)
        run(["git", "commit", "-m", "install hook assets"], cwd=self.root, env=self.env)
        self.head = self.rev("HEAD")

    def close(self) -> None:
        self.temp.cleanup()

    def _copy_project_files(self) -> None:
        hook_source = SOURCE_ROOT / ".githooks/pre-push"
        implementation_source = SOURCE_ROOT / "tools/git-hooks/hooks.py"
        if not hook_source.exists() or not implementation_source.exists():
            raise AssertionError("pre-push hook implementation is missing")
        hook_target = self.root / ".githooks/pre-push"
        implementation_target = self.root / "tools/git-hooks/hooks.py"
        gate_target = self.root / "tools/quality/quality-gate.sh"
        hook_target.parent.mkdir(parents=True)
        implementation_target.parent.mkdir(parents=True)
        gate_target.parent.mkdir(parents=True)
        shutil.copy2(hook_source, hook_target)
        shutil.copy2(implementation_source, implementation_target)
        gate_target.write_text(
            "#!/usr/bin/env bash\n"
            "set -eu\n"
            "printf '%s\\n' \"$@\" >\"$GATE_LOG\"\n"
            "if [ \"${GATE_SIGNAL:-}\" = TERM ]; then kill -TERM \"$$\"; fi\n"
            "exit \"${GATE_EXIT:-0}\"\n",
            encoding="utf-8",
        )
        hook_target.chmod(0o755)
        gate_target.chmod(0o755)

    def rev(self, revision: str) -> str:
        return run(
            ["git", "rev-parse", revision], cwd=self.root, env=self.env
        ).stdout.strip()

    def git(self, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run(["git", *arguments], cwd=self.root, env=self.env, check=check)

    def command(self, action: str) -> subprocess.CompletedProcess[str]:
        return run(
            ["python3", "tools/git-hooks/hooks.py", action],
            cwd=self.root,
            env=self.env,
            check=False,
        )

    def record(
        self,
        *,
        local_ref: str = "refs/heads/main",
        local_oid: str | None = None,
        remote_ref: str = "refs/heads/main",
        remote_oid: str | None = None,
    ) -> str:
        zero = "0" * len(self.head)
        return (
            f"{local_ref} {local_oid or self.head} "
            f"{remote_ref} {remote_oid or self.remote_base}\n"
        ).replace("<zero>", zero)

    def invoke(
        self,
        records: str,
        *,
        remote_name: str = "origin",
        remote_location: str | None = None,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = self.env.copy()
        if extra_env:
            environment.update(extra_env)
        return run(
            [str(self.root / ".githooks/pre-push"), remote_name, remote_location or str(self.remote)],
            cwd=self.root,
            env=environment,
            input_text=records,
            check=False,
        )

    def gate_arguments(self) -> list[str]:
        return self.gate_log.read_text(encoding="utf-8").splitlines()


class GitHookTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = HookRepository()

    def tearDown(self) -> None:
        self.fixture.close()

    def test_install_is_local_and_idempotent(self) -> None:
        first = self.fixture.command("install")
        second = self.fixture.command("install")
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(".githooks", self.fixture.git("config", "--local", "--get", "core.hooksPath").stdout.strip())
        self.assertNotIn("worktreeConfig", self.fixture.git("config", "--local", "--list").stdout)
        self.assertEqual(1, len(self.fixture.git("config", "--local", "--get-all", "core.hooksPath").stdout.splitlines()))

    def test_install_protects_foreign_and_multiple_local_values(self) -> None:
        self.fixture.git("config", "--local", "--add", "core.hooksPath", "foreign")
        result = self.fixture.command("install")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("foreign", self.fixture.git("config", "--local", "--get", "core.hooksPath").stdout.strip())
        self.fixture.git("config", "--local", "--add", "core.hooksPath", ".githooks")
        result = self.fixture.command("install")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(self.fixture.git("config", "--local", "--get-all", "core.hooksPath").stdout.splitlines()))

    def test_install_protects_global_value_without_changing_it(self) -> None:
        self.fixture.git("config", "--global", "core.hooksPath", "global-sentinel")
        result = self.fixture.command("install")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("global-sentinel", self.fixture.git("config", "--global", "--get", "core.hooksPath").stdout.strip())
        self.assertNotEqual(0, self.fixture.git("config", "--local", "--get", "core.hooksPath", check=False).returncode)

    def test_uninstall_only_removes_owned_local_value(self) -> None:
        self.fixture.git("config", "--global", "core.hooksPath", "global-sentinel")
        self.fixture.git("config", "--local", "core.hooksPath", ".githooks")
        result = self.fixture.command("uninstall")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("global-sentinel", self.fixture.git("config", "--global", "--get", "core.hooksPath").stdout.strip())
        self.assertNotEqual(0, self.fixture.git("config", "--local", "--get", "core.hooksPath", check=False).returncode)
        self.assertEqual(0, self.fixture.command("uninstall").returncode)
        self.fixture.git("config", "--local", "core.hooksPath", "foreign")
        self.assertNotEqual(0, self.fixture.command("uninstall").returncode)
        self.assertEqual("foreign", self.fixture.git("config", "--local", "--get", "core.hooksPath").stdout.strip())

    def test_install_rejects_untracked_wrong_mode_and_symlink_hook(self) -> None:
        self.fixture.git("rm", "--cached", ".githooks/pre-push")
        self.assertNotEqual(0, self.fixture.command("install").returncode)
        self.fixture.git("reset", "--hard", "HEAD")
        hook = self.fixture.root / ".githooks/pre-push"
        hook.chmod(0o644)
        self.assertNotEqual(0, self.fixture.command("install").returncode)
        hook.unlink()
        hook.symlink_to("../tools/git-hooks/hooks.py")
        self.assertNotEqual(0, self.fixture.command("install").returncode)

    def test_existing_ref_uses_advertised_remote_old_commit(self) -> None:
        result = self.fixture.invoke(self.fixture.record())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["auto", "--base", self.fixture.remote_base, "--head", self.fixture.head, "--ref", "pre-push:refs/heads/main"],
            self.fixture.gate_arguments(),
        )

    def test_new_ref_uses_remote_default_head_merge_base(self) -> None:
        result = self.fixture.invoke(
            self.fixture.record(remote_ref="refs/heads/feature", remote_oid="<zero>")
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.fixture.remote_base, self.fixture.gate_arguments()[2])
        self.assertEqual("pre-push:refs/heads/feature", self.fixture.gate_arguments()[-1])

    def test_multiple_refs_aggregate_to_oldest_ancestor_and_run_once(self) -> None:
        second_base = self.fixture.head
        (self.fixture.root / "next.txt").write_text("next\n", encoding="utf-8")
        self.fixture.git("add", "next.txt")
        self.fixture.git("commit", "-m", "next")
        self.fixture.head = self.fixture.rev("HEAD")
        records = self.fixture.record(remote_ref="refs/heads/main", remote_oid=self.fixture.remote_base)
        records += self.fixture.record(local_ref="refs/heads/topic", remote_ref="refs/heads/topic", remote_oid=second_base)
        result = self.fixture.invoke(records)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.fixture.remote_base, self.fixture.gate_arguments()[2])
        self.assertEqual("pre-push:refs/heads/main,refs/heads/topic", self.fixture.gate_arguments()[-1])

    def test_mixed_delete_is_validated_but_not_used_as_base(self) -> None:
        zero = "0" * len(self.fixture.head)
        records = self.fixture.record()
        records += self.fixture.record(local_ref="(delete)", local_oid=zero, remote_ref="refs/heads/old", remote_oid=self.fixture.remote_base)
        result = self.fixture.invoke(records)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.fixture.remote_base, self.fixture.gate_arguments()[2])

    def test_delete_only_fails_closed(self) -> None:
        zero = "0" * len(self.fixture.head)
        result = self.fixture.invoke(self.fixture.record(local_ref="(delete)", local_oid=zero))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.fixture.gate_log.exists())

    def test_every_non_delete_oid_must_equal_checkout_head(self) -> None:
        result = self.fixture.invoke(self.fixture.record(local_oid=self.fixture.remote_base))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.fixture.gate_log.exists())

    def test_rejects_malformed_oid_ref_and_duplicates(self) -> None:
        cases = [
            "only three fields here\n",
            self.fixture.record(local_oid="f" * 64),
            self.fixture.record(local_ref="not-a-ref"),
            self.fixture.record() + self.fixture.record(local_ref="refs/heads/other"),
        ]
        for records in cases:
            with self.subTest(records=records[:40]):
                self.fixture.gate_log.unlink(missing_ok=True)
                result = self.fixture.invoke(records)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.fixture.gate_log.exists())

    def test_missing_remote_commit_and_divergence_fail_closed(self) -> None:
        missing = "1" * len(self.fixture.head)
        self.assertNotEqual(0, self.fixture.invoke(self.fixture.record(remote_oid=missing)).returncode)
        self.fixture.gate_log.unlink(missing_ok=True)
        self.fixture.git("checkout", "--orphan", "other")
        self.fixture.git("rm", "-rf", ".")
        (self.fixture.root / "other.txt").write_text("other\n", encoding="utf-8")
        self.fixture.git("add", "other.txt")
        self.fixture.git("commit", "-m", "other root")
        divergent = self.fixture.rev("HEAD")
        self.fixture.git("checkout", "main")
        self.assertNotEqual(0, self.fixture.invoke(self.fixture.record(remote_oid=divergent)).returncode)
        self.assertFalse(self.fixture.gate_log.exists())

    def test_missing_default_head_and_missing_merge_base_fail_closed(self) -> None:
        empty_remote = Path(self.fixture.temp.name) / "empty.git"
        run(["git", "init", "--bare", str(empty_remote)], cwd=self.fixture.root, env=self.fixture.env)
        result = self.fixture.invoke(
            self.fixture.record(remote_ref="refs/heads/new", remote_oid="<zero>"),
            remote_location=str(empty_remote),
        )
        self.assertNotEqual(0, result.returncode)
        self.fixture.git("checkout", "--orphan", "remote-root")
        self.fixture.git("rm", "-rf", ".")
        (self.fixture.root / "remote.txt").write_text("remote\n", encoding="utf-8")
        self.fixture.git("add", "remote.txt")
        self.fixture.git("commit", "-m", "remote root")
        self.fixture.git("push", "--force", "origin", "HEAD:main")
        self.fixture.git("checkout", "main")
        result = self.fixture.invoke(self.fixture.record(remote_ref="refs/heads/new", remote_oid="<zero>"))
        self.assertNotEqual(0, result.returncode)

    def test_dirty_checkout_fails_for_staged_unstaged_and_untracked(self) -> None:
        for name in ("staged", "unstaged", "untracked"):
            with self.subTest(name=name):
                if name == "untracked":
                    (self.fixture.root / "secret-token.txt").write_text(
                        "do-not-print\n", encoding="utf-8"
                    )
                else:
                    (self.fixture.root / "README.md").write_text(
                        f"{name}\n", encoding="utf-8"
                    )
                    if name == "staged":
                        self.fixture.git("add", "README.md")
                result = self.fixture.invoke(self.fixture.record())
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("secret-token", result.stderr)
                self.assertNotIn("do-not-print", result.stderr)
                self.assertFalse(self.fixture.gate_log.exists())
                self.fixture.git("reset", "--hard", "HEAD")
                (self.fixture.root / "secret-token.txt").unlink(missing_ok=True)

    def test_ignored_untracked_file_is_allowed(self) -> None:
        (self.fixture.root / ".git/info/exclude").write_text("ignored.tmp\n", encoding="utf-8")
        (self.fixture.root / "ignored.tmp").write_text("ignored\n", encoding="utf-8")
        result = self.fixture.invoke(self.fixture.record())
        self.assertEqual(0, result.returncode, result.stderr)

    def test_hidden_index_flags_fail_closed(self) -> None:
        for flag in ("--assume-unchanged", "--skip-worktree"):
            with self.subTest(flag=flag):
                self.fixture.git("update-index", flag, "README.md")
                result = self.fixture.invoke(self.fixture.record())
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.fixture.gate_log.exists())
                undo = "--no-assume-unchanged" if flag == "--assume-unchanged" else "--no-skip-worktree"
                self.fixture.git("update-index", undo, "README.md")

    def test_diagnostics_do_not_disclose_remote_credentials_or_paths(self) -> None:
        secret_location = "https://alice:super-secret@example.invalid/private.git"
        result = self.fixture.invoke(
            self.fixture.record(remote_ref="refs/heads/new", remote_oid="<zero>"),
            remote_location=secret_location,
        )
        self.assertNotEqual(0, result.returncode)
        combined = result.stdout + result.stderr
        self.assertNotIn("alice", combined)
        self.assertNotIn("super-secret", combined)
        self.assertNotIn("private.git", combined)
        self.assertLessEqual(len(combined.splitlines()), 4)

    def test_gate_exit_code_and_signal_are_preserved(self) -> None:
        failed = self.fixture.invoke(self.fixture.record(), extra_env={"GATE_EXIT": "37"})
        self.assertEqual(37, failed.returncode)
        signaled = self.fixture.invoke(self.fixture.record(), extra_env={"GATE_SIGNAL": "TERM"})
        self.assertEqual(-signal.SIGTERM, signaled.returncode)


if __name__ == "__main__":
    unittest.main()
