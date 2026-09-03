from __future__ import annotations

import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock


TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))

from delivery_contract import (  # noqa: E402
    AcceptanceInput,
    EXPECTED_ROUTES,
    final_acceptance_status,
    parse_routes,
    validate_repository,
)


class DeliveryForwardTest(unittest.TestCase):
    def isolated_checkout(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name) / "checkout"
        root.mkdir()
        shutil.copy2(REPO / "AGENTS.md", root / "AGENTS.md")
        shutil.copytree(REPO / ".agents", root / ".agents", symlinks=True)
        shutil.copytree(REPO / "docs", root / "docs")
        shutil.copytree(REPO / "tools/agent-delivery", root / "tools/agent-delivery")
        (root / "tools/quality/checks.d/common").mkdir(parents=True)
        shutil.copy2(
            REPO / "tools/quality/checks.d/common/10-agent-delivery",
            root / "tools/quality/checks.d/common/10-agent-delivery",
        )
        return temporary, root

    def test_isolated_checkout_needs_no_personal_skill_directory(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        isolated_home = Path(temporary.name) / "empty-home"
        isolated_home.mkdir()
        with mock.patch.dict(
            os.environ,
            {"HOME": str(isolated_home), "CODEX_HOME": str(isolated_home / "none")},
            clear=True,
        ):
            self.assertEqual([], validate_repository(root))

    def test_forward_route_selects_one_implementation_path(self) -> None:
        routes = parse_routes(REPO / "docs/agents/delivery-workflow.md")
        self.assertEqual(EXPECTED_ROUTES, routes)
        self.assertEqual("implement", routes["one-ticket"])
        self.assertEqual("implement-frontier", routes["ticket-dag"])
        self.assertNotEqual(routes["one-ticket"], routes["ticket-dag"])

    def test_failed_or_stale_evidence_cannot_report_pass(self) -> None:
        passing = AcceptanceInput(0, "head", "head", 0, 0, 0, True)
        self.assertEqual("PASS", final_acceptance_status(passing))
        for changed in (
            AcceptanceInput(1, "head", "head", 0, 0, 0, True),
            AcceptanceInput(0, "old", "head", 0, 0, 0, True),
            AcceptanceInput(0, "head", "head", 1, 0, 0, True),
            AcceptanceInput(0, "head", "head", 0, 1, 0, True),
            AcceptanceInput(0, "head", "head", 0, 0, 1, True),
            AcceptanceInput(0, "head", "head", 0, 0, 0, False),
        ):
            self.assertEqual("FAIL", final_acceptance_status(changed))

    def test_mutating_capabilities_remain_explicit(self) -> None:
        standards = (REPO / "docs/agents/delivery-standards.md").read_text()
        for capability in (
            "create commits",
            "create or remove branches and worktrees",
            "push, publish, deploy",
            "update or close tracker records",
        ):
            self.assertIn(capability, standards)
        self.assertIn("user explicitly invokes it", standards)

    def test_broken_repository_reference_fails_closed(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        (root / "docs/agents/delivery-standards.md").unlink()
        failures = validate_repository(root)
        self.assertTrue(failures)
        self.assertTrue(any("missing repository delivery entry" in item for item in failures))

    def test_removed_project_skills_fail_closed(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        shutil.rmtree(root / ".agents/skills")
        failures = validate_repository(root)
        self.assertTrue(failures)
        self.assertTrue(any("project Skill set differs" in item for item in failures))


if __name__ == "__main__":
    unittest.main()
