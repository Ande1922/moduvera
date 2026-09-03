from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace


TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))

from delivery_contract import (  # noqa: E402
    AcceptanceInput,
    DECLARED_DEPENDENCY_ROOTS,
    EXPECTED_ROUTES,
    ImplementationRoute,
    ImplementationShape,
    final_acceptance_status,
    implementation_route,
    parse_routes,
    validate_repository,
)


class DeliveryForwardTest(unittest.TestCase):
    def isolated_checkout(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name) / "checkout"
        root.mkdir()
        for relative in DECLARED_DEPENDENCY_ROOTS:
            source = REPO / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(
                    source,
                    target,
                    symlinks=True,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
                )
            else:
                shutil.copy2(source, target)
        return temporary, root

    @staticmethod
    def isolated_environment(home: Path) -> dict[str, str]:
        return {
            "HOME": str(home),
            "CODEX_HOME": str(home / "no-personal-skills"),
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "LANG": os.environ.get("LANG", "C.UTF-8"),
        }

    @staticmethod
    def passing_acceptance() -> AcceptanceInput:
        return AcceptanceInput(
            gate_exit=0,
            gate_base="base",
            gate_head="head",
            gate_ref="refs/heads/change",
            gate_profile="normal",
            delivered_base="base",
            delivered_head="head",
            delivered_ref="refs/heads/change",
            delivered_profile="normal",
            reviewed_base="base",
            reviewed_head="head",
            standards_review_complete=True,
            spec_review_complete=True,
            unresolved_findings=0,
            missing_criteria=0,
            missing_scenarios=0,
            evidence_exists=True,
            checkout_clean=True,
        )

    def test_isolated_checkout_needs_no_personal_skill_directory(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        isolated_home = Path(temporary.name) / "empty-home"
        isolated_home.mkdir()
        environment = self.isolated_environment(isolated_home)

        validation = subprocess.run(
            [sys.executable, str(root / "tools/agent-delivery/validate.py"), "--repo", str(root)],
            cwd=root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, validation.returncode, validation.stderr)
        self.assertIn("representative forward evidence", validation.stdout)

        gate_help = subprocess.run(
            [str(root / "tools/quality/quality-gate.sh"), "--help"],
            cwd=root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, gate_help.returncode, gate_help.stderr)
        self.assertIn("Run the repository quality gate", gate_help.stdout)

    def test_forward_route_selects_one_implementation_path(self) -> None:
        routes = parse_routes(REPO / "docs/agents/delivery-workflow.md")
        self.assertEqual(EXPECTED_ROUTES, routes)
        self.assertEqual("implement", routes["one-ticket"])
        self.assertEqual("implement-frontier", routes["ticket-dag"])
        self.assertNotEqual(routes["one-ticket"], routes["ticket-dag"])

        single_with_reviews = implementation_route(ImplementationShape(
            ticket_count=1,
            later_independent_reviews=True,
        ))
        self.assertEqual(
            ImplementationRoute("implement", "implementation-evidence"),
            single_with_reviews,
        )
        self.assertEqual(
            "implement-frontier",
            implementation_route(ImplementationShape(ticket_count=2)).skill,
        )
        self.assertEqual(
            "implement-frontier",
            implementation_route(ImplementationShape(
                ticket_count=1,
                dependency_edges=1,
            )).skill,
        )
        self.assertEqual(
            "implement-frontier",
            implementation_route(ImplementationShape(
                ticket_count=1,
                isolated_writers=True,
            )).skill,
        )

    def test_failed_or_stale_evidence_cannot_report_pass(self) -> None:
        passing = self.passing_acceptance()
        self.assertEqual("PASS", final_acceptance_status(passing))
        for changed in (
            replace(passing, gate_exit=1),
            replace(passing, gate_base="old"),
            replace(passing, gate_head="old"),
            replace(passing, gate_ref="refs/heads/other"),
            replace(passing, gate_profile="docs-only"),
            replace(passing, reviewed_base="old"),
            replace(passing, reviewed_head="old"),
            replace(passing, reviewed_base=""),
            replace(passing, reviewed_head=""),
            replace(passing, standards_review_complete=False),
            replace(passing, spec_review_complete=False),
            replace(passing, unresolved_findings=1),
            replace(passing, missing_criteria=1),
            replace(passing, missing_scenarios=1),
            replace(passing, evidence_exists=False),
            replace(passing, checkout_clean=False),
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
        self.assertTrue(any("required project Skills are missing" in item for item in failures))

    def test_future_project_skill_is_allowed_and_structurally_validated(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        extra = root / ".agents/skills/add-business-service/SKILL.md"
        extra.parent.mkdir()
        extra.write_text(
            """---
name: add-business-service
description: Route a new business-service request into the repository delivery workflow.
---

# Add Business Service

Use the repository delivery workflow.

## Completion

Stop after reporting the selected delivery stage.
""",
            encoding="utf-8",
        )
        self.assertEqual([], validate_repository(root))

        extra.write_text(extra.read_text().replace(
            "name: add-business-service",
            "name: wrong-name",
        ))
        failures = validate_repository(root)
        self.assertTrue(any("frontmatter name must be add-business-service" in item
                            for item in failures))

    def test_cross_platform_personal_skill_paths_fail_closed(self) -> None:
        for personal_path in (
            "/root/.codex/skills/private/SKILL.md",
            r"C:\Users\maintainer\.agents\skills\private\SKILL.md",
        ):
            with self.subTest(personal_path=personal_path):
                temporary, root = self.isolated_checkout()
                self.addCleanup(temporary.cleanup)
                standards = root / "docs/agents/delivery-standards.md"
                standards.write_text(
                    standards.read_text(encoding="utf-8")
                    + f"\nPrivate dependency: `{personal_path}`\n",
                    encoding="utf-8",
                )

                failures = validate_repository(root)

                self.assertTrue(any(
                    "personal Skill path or username is forbidden" in item
                    for item in failures
                ))

    def test_quality_extension_fails_when_delivery_validator_is_missing(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        (root / "tools/agent-delivery/validate.py").unlink()
        result = subprocess.run(
            [str(root / "tools/quality/checks.d/common/10-agent-delivery")],
            cwd=root,
            env=self.isolated_environment(Path(temporary.name) / "empty-home"),
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("validation is required", result.stderr)

    def test_quality_extension_fails_when_delivery_test_runner_is_unusable(self) -> None:
        for failure_mode in ("missing", "not-executable"):
            with self.subTest(failure_mode=failure_mode):
                temporary, root = self.isolated_checkout()
                self.addCleanup(temporary.cleanup)
                runner = root / "tools/agent-delivery/test/run-tests.sh"
                if failure_mode == "missing":
                    runner.unlink()
                else:
                    runner.chmod(0o644)

                result = subprocess.run(
                    [str(root / "tools/quality/checks.d/common/10-agent-delivery")],
                    cwd=root,
                    env=self.isolated_environment(Path(temporary.name) / "empty-home"),
                    text=True,
                    capture_output=True,
                    check=False,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn("forward tests are required", result.stderr)


if __name__ == "__main__":
    unittest.main()
