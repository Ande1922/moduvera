from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

from tools.quality.test.test_quality_gate import RepositoryFixture


SCRIPT = Path(__file__).resolve().parents[1] / "review_preflight.py"
sys.path.insert(0, str(SCRIPT.parent))
import review_preflight  # noqa: E402


class ReviewPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = RepositoryFixture()
        self.addCleanup(self.fixture.close)
        self.fixture.write("README.md", "# Fixture\n")
        self.base = self.fixture.commit("base")

    def run_preflight(self, head: str, base: str | None = None, *options: str):
        result = subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.fixture.root),
             "--base", base or self.base, "--head", head, *options],
            text=True, capture_output=True,
        )
        self.assertTrue(result.stdout, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, report["exit_code"])
        return result.returncode, report

    def test_later_success_does_not_mask_whitespace_failure(self) -> None:
        self.fixture.write("README.md", "# Fixture  \n")
        head = self.fixture.commit("whitespace")
        code, report = self.run_preflight(head)
        self.assertNotEqual(0, code)
        self.assertEqual("FAIL", report["status"])
        self.assertNotEqual(0, report["checks"][0]["exit_code"])
        self.assertTrue(all(check["exit_code"] == 0 for check in report["checks"][1:]))
        self.assertIn("README.md", report["checks"][0]["output"])

    def test_checks_committed_range_without_changing_dirty_worktree(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        self.fixture.write("README.md", "[dirty](missing.md)  \n")
        before = self.fixture.git("status", "--porcelain")
        code, report = self.run_preflight(head)
        self.assertEqual(0, code)
        self.assertEqual((self.base, head), (report["base"], report["head"]))
        self.assertEqual(before, self.fixture.git("status", "--porcelain"))
        self.assertEqual("[dirty](missing.md)  \n", (self.fixture.root / "README.md").read_text())
        self.assertEqual("committed-range", report["scope"])

    def test_current_clean_receipt_identifies_registered_checkout(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        branch = self.fixture.git("branch", "--show-current")
        code, report = self.run_preflight(
            head, None, "--require-current-clean", "--expected-branch", branch,
        )
        self.assertEqual(0, code)
        self.assertEqual("current-clean-checkout", report["scope"])
        self.assertEqual({"worktree": str(self.fixture.root.resolve()),
                          "branch": branch, "head": head}, report["checkout"])
        self.assertEqual(1, report["changed_file_count"])
        self.assertRegex(report["diff_sha256"], r"^[0-9a-f]{64}$")

    def test_fingerprint_binds_committed_diff_not_ref_spelling_or_dirty_files(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        code, original = self.run_preflight(head)
        self.assertEqual(0, code)
        self.fixture.write("README.md", "uncommitted text\n")
        code, aliased = self.run_preflight("HEAD", "HEAD~1")
        self.assertEqual(0, code)
        self.assertEqual(original["diff_sha256"], aliased["diff_sha256"])
        changed_head = self.fixture.commit("changed")
        code, changed = self.run_preflight(changed_head)
        self.assertEqual(0, code)
        self.assertNotEqual(original["diff_sha256"], changed["diff_sha256"])

    def test_current_clean_mode_rejects_dirty_tracked_and_untracked_files(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        for path in ("README.md", "untracked.txt"):
            with self.subTest(path=path):
                self.fixture.write(path, "dirty\n")
                before = self.fixture.git("status", "--porcelain")
                code, report = self.run_preflight(head, None, "--require-current-clean")
                self.assertNotEqual(0, code)
                self.assertIn("checkout must be clean", report["error"])
                self.assertEqual(before, self.fixture.git("status", "--porcelain"))
                if path == "README.md":
                    self.fixture.write(path, "# Updated\n")

    def test_current_clean_mode_rejects_different_head_but_historical_review_works(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        reviewed = self.fixture.commit("reviewed")
        self.fixture.write("README.md", "# Later\n")
        self.fixture.commit("later")
        self.assertEqual(0, self.run_preflight(reviewed)[0])
        code, report = self.run_preflight(reviewed, None, "--require-current-clean")
        self.assertNotEqual(0, code)
        self.assertIn("does not match checked-out HEAD", report["error"])

    def test_registered_branch_mismatch_and_ambiguous_mode_fail(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        code, report = self.run_preflight(
            head, None, "--require-current-clean", "--expected-branch", "wrong-branch",
        )
        self.assertNotEqual(0, code)
        self.assertIn("registered --expected-branch", report["error"])
        code, report = self.run_preflight(head, None, "--expected-branch", "wrong-branch")
        self.assertNotEqual(0, code)
        self.assertIn("requires --require-current-clean", report["error"])

    def test_current_clean_mode_rejects_hidden_index_changes(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")
        self.fixture.git("update-index", "--assume-unchanged", "README.md")
        self.fixture.write("README.md", "hidden change\n")
        self.assertEqual("", self.fixture.git("status", "--porcelain"))
        code, report = self.run_preflight(head, None, "--require-current-clean")
        self.assertNotEqual(0, code)
        self.assertIn("index flags are forbidden", report["error"])

    def test_checkout_change_during_checks_cannot_receive_a_current_receipt(self) -> None:
        self.fixture.write("README.md", "# Updated\n")
        head = self.fixture.commit("valid")

        def change_checkout(*_args):
            self.fixture.git("checkout", "-q", "-b", "changed-during-check")

        output = io.StringIO()
        with mock.patch.object(sys, "argv", [
            str(SCRIPT), "--repo", str(self.fixture.root), "--base", self.base,
            "--head", head, "--require-current-clean",
        ]), mock.patch.object(review_preflight.quality_gate, "check_skill_structure",
                              side_effect=change_checkout), contextlib.redirect_stdout(output):
            code = review_preflight.main()
        report = json.loads(output.getvalue())
        self.assertNotEqual(0, code)
        self.assertEqual(code, report["exit_code"])
        self.assertIn("checkout identity changed", report["error"])

    def test_broken_link_fails(self) -> None:
        self.fixture.write("README.md", "[missing](missing.md)\n")
        code, report = self.run_preflight(self.fixture.commit("broken link"))
        self.assertNotEqual(0, code)
        self.assertIn("missing local link target", report["checks"][1]["output"])

    def test_invalid_skill_fails(self) -> None:
        self.fixture.write(".agents/skills/example/SKILL.md", "# Missing frontmatter\n")
        code, report = self.run_preflight(self.fixture.commit("invalid skill"))
        self.assertNotEqual(0, code)
        self.assertIn("frontmatter", report["checks"][2]["output"])

    def test_invalid_and_empty_ranges_fail(self) -> None:
        for base in ("absent-ref", self.base):
            with self.subTest(base=base):
                code, report = self.run_preflight(self.base, base)
                self.assertNotEqual(0, code)
                self.assertEqual("FAIL", report["status"])

    def test_divergent_range_fails(self) -> None:
        self.fixture.write("left.md", "left\n")
        left = self.fixture.commit("left")
        self.fixture.git("checkout", "-q", "-b", "right", self.base)
        self.fixture.write("right.md", "right\n")
        right = self.fixture.commit("right")
        code, report = self.run_preflight(right, left)
        self.assertNotEqual(0, code)
        self.assertIn("ancestor", report["error"])


if __name__ == "__main__":
    unittest.main()
