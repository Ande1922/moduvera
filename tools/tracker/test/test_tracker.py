from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check.py"
class TrackerCheckerTest(unittest.TestCase):
    def fixture(self, name: str) -> Path:
        source = Path(__file__).parent / "fixtures" / name
        temporary = Path(tempfile.mkdtemp(prefix="tracker-fixture-"))
        self.addCleanup(shutil.rmtree, temporary)
        shutil.copytree(source, temporary, dirs_exist_ok=True)
        return temporary

    def run_checker(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(CHECKER), "--root", str(root)],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_legal_tracker_passes(self) -> None:
        result = self.run_checker(self.fixture("legal"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("tracker consistency: PASS", result.stdout)

    def test_historical_bold_metadata_and_inline_answer_pass(self) -> None:
        result = self.run_checker(self.fixture("historical"))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_invalid_status_missing_blocker_and_unhandled_acceptance_fail(self) -> None:
        result = self.run_checker(self.fixture("invalid"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid for issue", result.stderr)
        self.assertIn("does not exist", result.stderr)
        self.assertIn("unhandled acceptance item", result.stderr)

    def test_cycle_fails_closed(self) -> None:
        root = self.fixture("legal")
        first = root / ".scratch/example/issues/01-first.md"
        second = root / ".scratch/example/issues/02-second.md"
        first.write_text(
            first.read_text()
            .replace("Blocked by: None", "Blocked by: 02")
            .replace("Status: resolved", "Status: blocked")
        )
        second.write_text(
            second.read_text().replace("Status: ready-for-agent", "Status: blocked")
        )
        result = self.run_checker(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cyclic blocker dependency", result.stderr)

    def test_resolved_spec_requires_terminal_children_and_converse(self) -> None:
        root = self.fixture("legal")
        spec = root / ".scratch/example/spec.md"
        spec.write_text(
            spec.read_text().replace("Status: ready-for-agent", "Status: resolved")
        )
        result = self.run_checker(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("resolved spec has non-terminal", result.stderr)

    def test_resolved_issue_requires_answer_verification_evidence(self) -> None:
        root = self.fixture("legal")
        issue = root / ".scratch/example/issues/01-first.md"
        issue.write_text(
            issue.read_text().replace(
                "Implemented by commit `1234567`; verification tests passed.",
                "The implementation exists.",
            )
        )
        result = self.run_checker(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Answer is missing verification evidence", result.stderr)


if __name__ == "__main__":
    unittest.main()
