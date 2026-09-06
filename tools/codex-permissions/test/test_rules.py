from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import unittest


REPO = Path(__file__).resolve().parents[3]
RULES = REPO / ".codex/rules/routine-git.rules"


class PortableGitRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.codex = shutil.which("codex")
        if cls.codex is None:
            raise RuntimeError("Install a Codex CLI with execpolicy check before running these tests")

    def decision(self, *command: str, rules: Path = RULES, cwd: Path = REPO):
        result = subprocess.run(
            [self.codex, "execpolicy", "check", "--rules", str(rules), "--", *command],
            cwd=cwd, text=True, capture_output=True, timeout=20,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout).get("decision")

    def test_routine_task_commands_are_allowed(self) -> None:
        commands = [
            ["git", "add", "--", "services/order", "docs/guide.md"],
            ["git", "commit", "-m", "fix: preserve request context"],
            ["git", "commit", "--message", "fix: preserve request context"],
            ["git", "commit", "-F", "commit-message.txt"],
            ["git", "commit", "--file", "commit-message.txt"],
            ["git", "worktree", "add", "--", "../worker one", "task-branch"],
            ["git", "worktree", "add", "-b", "task-branch", "--", "../worker", "HEAD"],
            ["git", "worktree", "add", "--detach", "--", "../review", "HEAD"],
            ["git", "switch", "--", "task-branch"],
            ["git", "switch", "-c", "task-branch", "--", "HEAD"],
            ["git", "switch", "--create", "task-branch", "--", "HEAD"],
            ["git", "checkout", "-b", "task-branch", "HEAD"],
            ["git", "merge", "--ff-only", "--", "reviewed-branch"],
            ["git", "fetch", "--", "origin"],
        ]
        for command in commands:
            with self.subTest(command=command):
                self.assertEqual("allow", self.decision(*command))

    def test_other_command_forms_receive_no_repository_allow(self) -> None:
        commands = [
            ["git", "push", "origin", "main"],
            ["git", "push", "--force", "origin", "main"],
            ["git", "reset", "--hard", "HEAD"],
            ["git", "clean", "-fd"],
            ["git", "commit", "--amend"],
            ["git", "commit", "--no-verify", "-m", "skip hooks"],
            ["git", "worktree", "remove", "../worker"],
            ["git", "worktree", "prune"],
            ["git", "worktree", "add", "--force", "../worker"],
            ["git", "worktree", "add", "-B", "task-branch", "../worker"],
            ["git", "switch", "--discard-changes", "main"],
            ["git", "switch", "-C", "task-branch"],
            ["git", "checkout", "--", "source.java"],
            ["git", "merge", "task-branch"],
            ["git", "rebase", "main"],
            ["git", "cherry-pick", "task-commit"],
            ["git", "pull"],
            ["git", "add", "-A"],
            ["git", "-C", "../elsewhere", "commit", "-m", "different invocation"],
            ["python3", "script.py"],
            ["docker", "compose", "up", "-d"],
            ["./mvnw", "deploy"],
            ["sh", "-c", "git commit -m example"],
        ]
        for command in commands:
            with self.subTest(command=command):
                self.assertIsNone(self.decision(*command))

    def test_rules_work_from_a_relocated_checkout_without_personal_paths(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-policy-check-") as directory:
            root = Path(directory) / "another developer checkout"
            target = root / ".codex/rules/routine-git.rules"
            target.parent.mkdir(parents=True)
            shutil.copy2(RULES, target)
            self.assertEqual("allow", self.decision(
                "git", "worktree", "add", "-b", "relocated", "--", "../worker", "HEAD",
                rules=target, cwd=root,
            ))
            self.assertEqual("allow", self.decision(
                "git", "commit", "-m", "fix: relocated checkout", rules=target, cwd=root,
            ))
            self.assertIsNone(self.decision("git", "push", rules=target, cwd=root))

    def test_stricter_rule_wins_when_config_layers_are_combined(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-policy-layer-") as directory:
            restrictive = Path(directory) / "restricted.rules"
            restrictive.write_text(
                'prefix_rule(pattern=["git", "commit"], decision="prompt")\n',
                encoding="utf-8",
            )
            result = subprocess.run(
                [self.codex, "execpolicy", "check", "--rules", str(RULES),
                 "--rules", str(restrictive), "--", "git", "commit", "-m", "example"],
                text=True, capture_output=True, timeout=20,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("prompt", json.loads(result.stdout)["decision"])

    def test_project_defaults_keep_automatic_review_without_host_permissions(self) -> None:
        config = tomllib.loads((REPO / ".codex/config.toml").read_text())
        self.assertEqual({"approval_policy": "on-request", "approvals_reviewer": "auto_review"}, config)


if __name__ == "__main__":
    unittest.main()
