from __future__ import annotations

import json
from pathlib import Path
import subprocess
import unittest

from tools.quality.test.test_quality_gate import RepositoryFixture


SCRIPT = Path(__file__).resolve().parents[1] / "review_preflight.py"


class ReviewPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = RepositoryFixture()
        self.addCleanup(self.fixture.close)
        self.fixture.write("README.md", "# Fixture\n")
        self.base = self.fixture.commit("base")

    def run_preflight(self, head: str, base: str | None = None):
        result = subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.fixture.root),
             "--base", base or self.base, "--head", head],
            text=True, capture_output=True,
        )
        self.assertTrue(result.stdout, result.stderr)
        return result.returncode, json.loads(result.stdout)

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
