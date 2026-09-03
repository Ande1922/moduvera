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

    def test_resolved_issue_requires_concrete_commit(self) -> None:
        root = self.fixture("legal")
        issue = root / ".scratch/example/issues/01-first.md"
        issue.write_text(issue.read_text().replace("commit `1234567`; ", ""))
        result = self.run_checker(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Answer is missing a concrete commit", result.stderr)

    def test_resolved_issue_rejects_negative_verification_wording(self) -> None:
        negative_answers = (
            "No tests were run; verification is unavailable/failed.",
            "Verification failed.",
            "Tests were not run; evidence is unavailable.",
            "Tests did not pass.",
        )
        for negative_answer in negative_answers:
            with self.subTest(answer=negative_answer):
                root = self.fixture("legal")
                issue = root / ".scratch/example/issues/01-first.md"
                issue.write_text(
                    issue.read_text().replace(
                        "verification tests passed.", negative_answer
                    )
                )
                result = self.run_checker(root)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(
                    "Answer is missing affirmative verification evidence", result.stderr
                )

    def test_blocker_entries_must_match_the_entire_field(self) -> None:
        for invalid in ("None 01", "01 02", "External", "1"):
            with self.subTest(blocked_by=invalid):
                root = self.fixture("legal")
                first = root / ".scratch/example/issues/01-first.md"
                first.write_text(
                    first.read_text().replace("Blocked by: None", f"Blocked by: {invalid}")
                )
                result = self.run_checker(root)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("invalid blocker entry", result.stderr)

    def test_wontfix_issue_and_spec_are_terminal_with_open_blocker(self) -> None:
        root = self.fixture("legal")
        spec = root / ".scratch/example/spec.md"
        second = root / ".scratch/example/issues/02-second.md"
        spec.write_text(spec.read_text().replace("ready-for-agent", "wontfix"))
        second.write_text(
            second.read_text()
            .replace("Status: ready-for-agent", "Status: wontfix")
            .replace("Blocked by: 01", "Blocked by: External — upstream cancelled")
        )
        result = self.run_checker(root)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
