from __future__ import annotations

import json
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
    AcceptanceConsumer,
    AppSupport,
    BusinessServiceDescription,
    DECLARED_DEPENDENCY_ROOTS,
    DeliveryContractError,
    DurableStateNeed,
    EXPECTED_ROUTES,
    ImplementationRoute,
    ImplementationShape,
    Participant,
    SupportPromise,
    business_service_plan,
    final_acceptance_status,
    implementation_route,
    load_business_service_contract,
    parse_routes,
    representative_business_service_description,
    representative_forward_failures,
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
    def mutate_shape_contract(root: Path, mutation: object) -> None:
        recipe = root / "docs/agents/new-business-service.md"
        text = recipe.read_text(encoding="utf-8")
        prefix = "<!-- business-service-contract:start -->\n```json\n"
        suffix = "\n```\n<!-- business-service-contract:end -->"
        start = text.index(prefix) + len(prefix)
        end = text.index(suffix, start)
        payload = json.loads(text[start:end])
        mutation(payload)  # type: ignore[operator]
        recipe.write_text(
            text[:start] + json.dumps(payload, indent=2) + text[end:],
            encoding="utf-8",
        )

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
        skill_text = (
            REPO / ".agents/skills/add-business-service/SKILL.md"
        ).read_text(encoding="utf-8")
        self.assertIn("Route by task shape", skill_text)
        for shape_key in EXPECTED_ROUTES:
            self.assertNotIn(f"`{shape_key}`", skill_text)

    def test_business_service_forward_plan_traces_complete_vertical_slices(self) -> None:
        contract = load_business_service_contract(
            REPO / "docs/agents/new-business-service.md"
        )
        description = representative_business_service_description()
        plan = business_service_plan(description, contract)

        self.assertEqual(description, plan.shape_card)
        self.assertEqual(
            "Decide returns and publish their outcome", plan.shape_card.capability
        )
        self.assertEqual("Returns Team", plan.shape_card.owner)
        self.assertEqual(description.use_cases, plan.shape_card.use_cases)
        self.assertEqual(description.dependencies, plan.shape_card.dependencies)
        self.assertEqual(description.apps, plan.shape_card.apps)
        self.assertEqual(description.durable_state, plan.shape_card.durable_state)
        self.assertEqual(
            (
                "capability, ownership, and use cases",
                "consumer and support promises",
                "permissions and contracts",
                "application, domain, and durable state",
                "adapters, app assemblies, and acceptance",
                "decisions, exclusions, and test seams",
            ),
            plan.spec_sections,
        )
        self.assertEqual(2, len(plan.tickets))
        self.assertEqual((), plan.tickets[0].blocked_by)
        self.assertEqual(("use-case-01",), plan.tickets[1].blocked_by)
        for ticket in plan.tickets:
            self.assertTrue(ticket.outcome.strip())
            self.assertTrue(ticket.support_claims)
            self.assertTrue(ticket.acceptance_criteria)
            self.assertTrue(ticket.test_seams)
            self.assertTrue(ticket.decisions)
            self.assertTrue(ticket.exclusions)
            self.assertTrue(ticket.artifacts)
        self.assertEqual(
            "implement-frontier",
            implementation_route(plan.implementation_shape).skill,
        )
        targets = {artifact.target for artifact in plan.artifacts}
        self.assertIn("services/returns/returns-api", targets)
        self.assertIn("services/returns/returns-service", targets)
        self.assertIn("returns HTTP inbound adapter", targets)
        self.assertIn("returns HTTP provider adapter", targets)
        self.assertIn("returns message inbound adapter", targets)
        self.assertIn("returns message outbound adapter", targets)
        self.assertIn("returns persistence outbound adapter for return-ledger", targets)
        self.assertIn("returns service-owned migration for return-ledger", targets)
        self.assertIn("real-database verification for return-ledger", targets)
        self.assertIn("returns-app assembly", targets)
        self.assertIn("fulfillment-app assembly", targets)
        self.assertIn("reference-product acceptance entry for Shopper", targets)
        self.assertIn("returns-app acceptance entry for Returns Operators", targets)
        self.assertIn("consumer contract acceptance for Fulfillment Partners", targets)
        self.assertIn(
            "repository message-contract verification for warehouse-command", targets
        )
        self.assertIn(
            "repository message-contract verification for accepted-event", targets
        )
        for promise in description.support_promises:
            self.assertIn(
                f"repository architecture rules for {promise.key}", targets
            )
        for artifact in plan.artifacts:
            self.assertTrue(artifact.consumers)
            self.assertTrue(artifact.use_cases)
            self.assertTrue(artifact.support_promises)
        artifacts = {artifact.target: artifact for artifact in plan.artifacts}
        self.assertEqual(
            ("Shopper",),
            artifacts[
                "reference-product acceptance entry for Shopper"
            ].acceptance_consumers,
        )
        self.assertEqual(
            ("Returns Operators",),
            artifacts[
                "returns-app acceptance entry for Returns Operators"
            ].acceptance_consumers,
        )
        self.assertEqual(
            ("Customer Support", "Fulfillment", "Returns Policy"),
            artifacts[
                "returns-app acceptance entry for Returns Operators"
            ].consumers,
        )
        self.assertEqual(
            ("Fulfillment Partners",),
            artifacts[
                "consumer contract acceptance for Fulfillment Partners"
            ].acceptance_consumers,
        )
        self.assertEqual(
            ("Finance", "Returns", "Warehouse"),
            artifacts[
                "consumer contract acceptance for Fulfillment Partners"
            ].consumers,
        )
        self.assertEqual(
            targets,
            {target for ticket in plan.tickets for target in ticket.artifacts},
        )
        self.assertFalse(any(
            reference in " ".join(targets).lower()
            for reference in ("catalog", "inventory", "order", "notes")
        ))

    def test_async_only_plan_has_contracts_without_a_synchronous_service_api(self) -> None:
        contract = load_business_service_contract(
            REPO / "docs/agents/new-business-service.md"
        )
        base = representative_business_service_description()
        command = base.support_promises[3]
        plan = business_service_plan(replace(
            base,
            use_cases=("Decide Return",),
            dependencies=(),
            support_promises=(command,),
            apps=(AppSupport(
                "fulfillment-app", "multi-service", (command.key,),
                "fulfillment App composition test",
            ),),
            acceptance_consumers=(AcceptanceConsumer(
                "Warehouse", "consumer-contract", "fulfillment-app",
                (command.key,), "warehouse contract scenario",
            ),),
            durable_state=(),
        ), contract)

        targets = {artifact.target for artifact in plan.artifacts}
        self.assertIn("services/returns/returns-api", targets)
        self.assertNotIn("returns synchronous Service API", targets)
        self.assertIn("returns message inbound adapter", targets)
        self.assertFalse(any("persistence" in target for target in targets))
        self.assertFalse(any("migration" in target for target in targets))

    def test_internal_only_and_declared_app_acceptance_omit_external_surfaces(self) -> None:
        contract = load_business_service_contract(
            REPO / "docs/agents/new-business-service.md"
        )
        base = representative_business_service_description()
        internal = base.support_promises[4]
        plan = business_service_plan(replace(
            base,
            use_cases=("Decide Return",),
            dependencies=(),
            support_promises=(internal,),
            apps=(AppSupport(
                "returns-app", "standalone", (internal.key,),
                "returns App startup test",
            ),),
            acceptance_consumers=(AcceptanceConsumer(
                "Returns Operators", "declared-app", "returns-app",
                (internal.key,), "returns App acceptance scenario",
            ),),
            durable_state=(),
        ), contract)

        targets = {artifact.target for artifact in plan.artifacts}
        self.assertNotIn("services/returns/returns-api", targets)
        self.assertNotIn("returns synchronous Service API", targets)
        self.assertFalse(any("adapter" in target.lower() for target in targets))
        self.assertFalse(any("reference-product" in target for target in targets))
        self.assertIn("returns-app acceptance entry for Returns Operators", targets)
        self.assertIn("services/returns/returns-service", targets)

    def test_business_service_plan_rejects_incomplete_or_unsafe_shapes(self) -> None:
        contract = load_business_service_contract(
            REPO / "docs/agents/new-business-service.md"
        )
        description = representative_business_service_description()
        with self.assertRaisesRegex(ValueError, "unsafe value|lowercase hyphenated"):
            business_service_plan(replace(description, name="../../returns"), contract)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            business_service_plan(replace(
                description,
                decisions=("One decision", "One decision"),
            ), contract)
        with self.assertRaisesRegex(ValueError, "blank or padded"):
            business_service_plan(replace(
                description,
                exclusions=(" ",),
            ), contract)

        remote = description.support_promises[1]
        with self.assertRaisesRegex(ValueError, "requires a provider adapter"):
            business_service_plan(replace(
                description,
                support_promises=tuple(
                    replace(promise, provider_adapter=None)
                    if promise.key == remote.key else promise
                    for promise in description.support_promises
                ),
            ), contract)
        with self.assertRaisesRegex(ValueError, "unknown App"):
            business_service_plan(replace(
                description,
                support_promises=tuple(
                    replace(promise, app="missing-app")
                    if promise.key == remote.key else promise
                    for promise in description.support_promises
                ),
            ), contract)
        with self.assertRaisesRegex(ValueError, "verification"):
            business_service_plan(replace(
                description,
                support_promises=tuple(
                    replace(promise, verification="")
                    if promise.key == remote.key else promise
                    for promise in description.support_promises
                ),
            ), contract)

    def test_acceptance_cannot_cross_single_or_multiple_promise_apps(self) -> None:
        contract = load_business_service_contract(
            REPO / "docs/agents/new-business-service.md"
        )
        description = representative_business_service_description()
        shopper = description.acceptance_consumers[0]
        with self.assertRaisesRegex(ValueError, "crosses promise App boundaries"):
            business_service_plan(replace(
                description,
                acceptance_consumers=(
                    replace(shopper, app="fulfillment-app"),
                    *description.acceptance_consumers[1:],
                ),
            ), contract)

        operators = description.acceptance_consumers[1]
        with self.assertRaisesRegex(ValueError, "crosses promise App boundaries"):
            business_service_plan(replace(
                description,
                acceptance_consumers=(
                    description.acceptance_consumers[0],
                    replace(
                        operators,
                        app="fulfillment-app",
                        support_promises=(
                            "fulfillment-local",
                            "support-remote",
                            "warehouse-command",
                            "policy-internal",
                        ),
                    ),
                    replace(
                        description.acceptance_consumers[2],
                        support_promises=("accepted-event",),
                    ),
                ),
            ), contract)

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
        extra = root / ".agents/skills/future-stage/SKILL.md"
        extra.parent.mkdir()
        extra.write_text(
            """---
name: future-stage
description: Route a future repository delivery stage.
---

# Future Stage

Use the repository delivery workflow.

## Completion

Stop after reporting the selected delivery stage.
""",
            encoding="utf-8",
        )
        self.assertEqual([], validate_repository(root))

        extra.write_text(extra.read_text().replace(
            "name: future-stage",
            "name: wrong-name",
        ))
        failures = validate_repository(root)
        self.assertTrue(any("frontmatter name must be future-stage" in item
                            for item in failures))

    def test_business_service_recipe_and_skill_are_required_and_link_checked(self) -> None:
        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        recipe = root / "docs/agents/new-business-service.md"
        recipe.unlink()

        failures = validate_repository(root)

        self.assertTrue(any("missing repository delivery entry" in item
                            and "new-business-service.md" in item
                            for item in failures))

        temporary, root = self.isolated_checkout()
        self.addCleanup(temporary.cleanup)
        skill = root / ".agents/skills/add-business-service/SKILL.md"
        skill.write_text(skill.read_text(encoding="utf-8").replace(
            "../../../docs/agents/new-business-service.md",
            "../../../docs/agents/missing-business-service-recipe.md",
        ), encoding="utf-8")

        failures = validate_repository(root)

        self.assertTrue(any("missing link target" in item
                            and "missing-business-service-recipe.md" in item
                            for item in failures))

    def test_shape_contract_mutations_fail_forward_validation(self) -> None:
        mutations = {
            "async-only synchronous API": lambda payload: payload["scenarios"][
                "message-command"
            ]["artifact_roles"].append("synchronous-api"),
            "internal-only adapter": lambda payload: payload["scenarios"][
                "internal"
            ].update({"provider_adapter": "required"}),
            "authorization omitted": lambda payload: payload[
                "required_promise_fields"
            ].remove("permission"),
            "empty internal API module": lambda payload: payload["scenarios"][
                "internal"
            ]["artifact_roles"].append("provider-api"),
            "scenario removed": lambda payload: payload["scenarios"].pop(
                "message-event"
            ),
            "App topology removed": lambda payload: payload[
                "app_topologies"
            ].remove("multi-service"),
            "acceptance topology removed": lambda payload: payload[
                "acceptance_topologies"
            ].pop("consumer-contract"),
            "architecture verification removed": lambda payload: payload[
                "scenarios"
            ]["http"]["artifact_roles"].remove("architecture-verification"),
            "message verification removed": lambda payload: payload[
                "scenarios"
            ]["message-command"]["artifact_roles"].remove(
                "message-verification"
            ),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                temporary, root = self.isolated_checkout()
                self.addCleanup(temporary.cleanup)
                self.mutate_shape_contract(root, mutation)

                failures = representative_forward_failures(root)

                self.assertTrue(failures, name)

    def test_malformed_shape_contracts_have_bounded_diagnostics(self) -> None:
        cases = (
            (
                "wrong scenarios type",
                lambda payload: payload.update({"scenarios": 7}),
                "Shape Contract invalid at scenarios: expected nonempty object",
            ),
            (
                "unknown nested key",
                lambda payload: payload["scenarios"]["http"].update(
                    {"unexpected": "value"}
                ),
                "Shape Contract invalid at scenarios.http: "
                "object keys do not match schema",
            ),
            (
                "numeric artifact roles",
                lambda payload: payload["scenarios"]["http"].update(
                    {"artifact_roles": 7}
                ),
                "Shape Contract invalid at scenarios.http.artifact_roles: "
                "expected nonempty string list",
            ),
            (
                "malformed template",
                lambda payload: payload["artifact_templates"].update(
                    {"service": "{service"}
                ),
                "Shape Contract invalid at artifact_templates.service: "
                "malformed template",
            ),
            (
                "unavailable placeholder",
                lambda payload: payload["artifact_templates"].update(
                    {"provider-api": "services/{consumer}/api"}
                ),
                "Shape Contract invalid at artifact_templates.provider-api: "
                "placeholder is unavailable at use site",
            ),
            (
                "field list wrong type",
                lambda payload: payload.update(
                    {"required_acceptance_fields": "name"}
                ),
                "Shape Contract invalid at required_acceptance_fields: "
                "expected nonempty string list",
            ),
        )
        for name, mutation, expected in cases:
            with self.subTest(name=name):
                temporary, root = self.isolated_checkout()
                self.addCleanup(temporary.cleanup)
                self.mutate_shape_contract(root, mutation)

                with self.assertRaises(DeliveryContractError) as caught:
                    load_business_service_contract(
                        root / "docs/agents/new-business-service.md"
                    )

                self.assertEqual(expected, str(caught.exception))
                self.assertLess(len(str(caught.exception)), 180)

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
