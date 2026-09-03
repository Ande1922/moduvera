"""Deterministic checks for the repository-owned agent delivery contract."""

from __future__ import annotations

from dataclasses import dataclass, replace
import json
from pathlib import Path
import re
from typing import Any


REQUIRED_SKILLS = (
    "add-business-service",
    "grill-with-docs",
    "to-spec",
    "to-tickets",
    "implement",
    "implement-frontier",
    "code-review",
    "tdd",
    "quality-gate",
    "final-acceptance",
)

DECLARED_DEPENDENCY_ROOTS = (
    "AGENTS.md",
    ".agents",
    "docs",
    "tools/agent-delivery",
    "tools/quality",
)

EXPECTED_ROUTES = {
    "decision-unclear": "grill-with-docs",
    "agreed-decision": "to-spec",
    "approved-spec": "to-tickets",
    "one-ticket": "implement",
    "ticket-dag": "implement-frontier",
    "fixed-diff-review": "code-review",
    "review-clean": "quality-gate",
    "gate-pass": "final-acceptance",
}

MARKDOWN_LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
PERSONAL_REFERENCE = re.compile(
    r"(?:/Users/|/home/[^/\s]+/|/root/\.(?:codex|agents)(?:/|\b)|"
    r"[A-Z]:[\\/]Users[\\/][^\\/\s]+[\\/]|"
    r"~/\.(?:codex|agents)(?:/|\b)|\$HOME/\.(?:codex|agents)(?:/|\b))",
    re.IGNORECASE,
)
SHAPE_CONTRACT = re.compile(
    r"<!-- business-service-contract:start -->\s*```json\s*(\{.*?\})\s*```\s*"
    r"<!-- business-service-contract:end -->",
    re.DOTALL,
)
SAFE_KEY = re.compile(r"[a-z][a-z0-9-]*")


class DeliveryContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class AcceptanceInput:
    gate_exit: int
    gate_base: str
    gate_head: str
    gate_ref: str
    gate_profile: str
    delivered_base: str
    delivered_head: str
    delivered_ref: str
    delivered_profile: str
    reviewed_base: str
    reviewed_head: str
    standards_review_complete: bool
    spec_review_complete: bool
    unresolved_findings: int
    missing_criteria: int
    missing_scenarios: int
    evidence_exists: bool
    checkout_clean: bool


@dataclass(frozen=True)
class ImplementationShape:
    ticket_count: int
    dependency_edges: int = 0
    isolated_writers: bool = False
    later_independent_reviews: bool = False


@dataclass(frozen=True)
class ImplementationRoute:
    skill: str
    stop_after: str


@dataclass(frozen=True)
class BusinessServiceContract:
    required_description_fields: tuple[str, ...]
    required_dependency_fields: tuple[str, ...]
    required_promise_fields: tuple[str, ...]
    required_app_fields: tuple[str, ...]
    required_acceptance_fields: tuple[str, ...]
    artifact_templates: dict[str, str]
    scenarios: dict[str, dict[str, Any]]
    state_required_fields: tuple[str, ...]
    state_artifact_roles: tuple[str, ...]
    app_topologies: tuple[str, ...]
    acceptance_topologies: dict[str, str]


@dataclass(frozen=True)
class Participant:
    role: str
    name: str


@dataclass(frozen=True)
class UseCaseDependency:
    blocked: str
    blocker: str


@dataclass(frozen=True)
class SupportPromise:
    key: str
    kind: str
    use_case: str
    participants: tuple[Participant, ...]
    permission: str
    app: str
    verification: str
    provider_adapter: str | None = None


@dataclass(frozen=True)
class DurableStateNeed:
    name: str
    use_case: str
    transaction_boundary: str
    tenant_isolation: str
    repository_seam: str
    migration: str
    verification: str


@dataclass(frozen=True)
class AppSupport:
    name: str
    topology: str
    support_promises: tuple[str, ...]
    verification: str


@dataclass(frozen=True)
class AcceptanceConsumer:
    name: str
    topology: str
    app: str
    support_promises: tuple[str, ...]
    verification: str


@dataclass(frozen=True)
class BusinessServiceDescription:
    name: str
    capability: str
    owner: str
    use_cases: tuple[str, ...]
    dependencies: tuple[UseCaseDependency, ...]
    support_promises: tuple[SupportPromise, ...]
    apps: tuple[AppSupport, ...]
    acceptance_consumers: tuple[AcceptanceConsumer, ...]
    decisions: tuple[str, ...]
    exclusions: tuple[str, ...]
    durable_state: tuple[DurableStateNeed, ...] = ()


@dataclass(frozen=True)
class PlannedArtifact:
    target: str
    consumers: tuple[str, ...]
    use_cases: tuple[str, ...]
    support_promises: tuple[str, ...]


@dataclass(frozen=True)
class BusinessServiceTicket:
    key: str
    outcome: str
    blocked_by: tuple[str, ...]
    support_claims: tuple[str, ...]
    acceptance_criteria: tuple[str, ...]
    test_seams: tuple[str, ...]
    decisions: tuple[str, ...]
    exclusions: tuple[str, ...]
    artifacts: tuple[str, ...]


@dataclass(frozen=True)
class BusinessServicePlan:
    spec_sections: tuple[str, ...]
    support_claims: tuple[str, ...]
    decisions: tuple[str, ...]
    exclusions: tuple[str, ...]
    tickets: tuple[BusinessServiceTicket, ...]
    artifacts: tuple[PlannedArtifact, ...]
    implementation_shape: ImplementationShape


def implementation_route(shape: ImplementationShape) -> ImplementationRoute:
    if shape.ticket_count < 1 or shape.dependency_edges < 0:
        raise ValueError("implementation shape requires tickets and non-negative edges")
    frontier = (
        shape.ticket_count > 1
        or shape.dependency_edges > 0
        or shape.isolated_writers
    )
    if frontier:
        return ImplementationRoute("implement-frontier", "frontier-evidence")
    return ImplementationRoute("implement", "implementation-evidence")


def _string_tuple(values: tuple[str, ...], label: str, *, required: bool = False) -> tuple[str, ...]:
    if required and not values:
        raise ValueError(f"{label} requires at least one value")
    normalized: list[str] = []
    for value in values:
        if not isinstance(value, str) or not value.strip() or value != value.strip():
            raise ValueError(f"{label} contains a blank or padded value")
        if (
            len(value) > 240
            or ".." in value
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
        ):
            raise ValueError(f"{label} contains an unsafe value: {value!r}")
        normalized.append(value)
    if len(normalized) != len(set(normalized)):
        raise ValueError(f"{label} contains duplicate values")
    return tuple(normalized)


def _label(value: str, label: str) -> str:
    return _string_tuple((value,), label, required=True)[0]


def _key(value: str, label: str) -> str:
    if not isinstance(value, str) or SAFE_KEY.fullmatch(value) is None:
        raise ValueError(f"{label} must be a lowercase hyphenated identifier")
    return value


def load_business_service_contract(recipe: Path) -> BusinessServiceContract:
    text = recipe.read_text(encoding="utf-8")
    matches = SHAPE_CONTRACT.findall(text)
    if len(matches) != 1:
        raise DeliveryContractError(f"{recipe}: exactly one Shape Contract is required")
    try:
        raw = json.loads(matches[0])
    except json.JSONDecodeError as error:
        raise DeliveryContractError(f"{recipe}: invalid Shape Contract JSON") from error
    if not isinstance(raw, dict) or raw.get("schema") != 1:
        raise DeliveryContractError(f"{recipe}: unsupported Shape Contract schema")
    try:
        durable = raw["durable_state"]
        contract = BusinessServiceContract(
            tuple(raw["required_description_fields"]),
            tuple(raw["required_dependency_fields"]),
            tuple(raw["required_promise_fields"]),
            tuple(raw["required_app_fields"]),
            tuple(raw["required_acceptance_fields"]),
            dict(raw["artifact_templates"]),
            dict(raw["scenarios"]),
            tuple(durable["required_fields"]),
            tuple(durable["artifact_roles"]),
            tuple(raw["app_topologies"]),
            dict(raw["acceptance_topologies"]),
        )
    except (KeyError, TypeError, ValueError) as error:
        raise DeliveryContractError(f"{recipe}: incomplete Shape Contract") from error
    required_description = {
        "name", "capability", "owner", "use_cases", "dependencies", "support_promises",
        "apps", "acceptance_consumers", "decisions", "exclusions",
    }
    required_promise = {
        "key", "kind", "use_case", "participants", "permission", "app",
        "verification",
    }
    required_app = {"name", "topology", "support_promises", "verification"}
    required_acceptance = {
        "name", "topology", "app", "support_promises", "verification",
    }
    scenarios = {
        "local-direct", "remote-direct", "http", "message-command",
        "message-event", "internal",
    }
    if set(contract.required_description_fields) != required_description:
        raise DeliveryContractError(f"{recipe}: incomplete description field contract")
    if len(contract.required_description_fields) != len(required_description):
        raise DeliveryContractError(f"{recipe}: duplicate description field contract")
    if set(contract.required_dependency_fields) != {"blocked", "blocker"}:
        raise DeliveryContractError(f"{recipe}: incomplete dependency field contract")
    if len(contract.required_dependency_fields) != 2:
        raise DeliveryContractError(f"{recipe}: duplicate dependency field contract")
    if set(contract.required_promise_fields) != required_promise:
        raise DeliveryContractError(f"{recipe}: incomplete support-promise field contract")
    if len(contract.required_promise_fields) != len(required_promise):
        raise DeliveryContractError(f"{recipe}: duplicate support-promise field contract")
    if set(contract.required_app_fields) != required_app:
        raise DeliveryContractError(f"{recipe}: incomplete App field contract")
    if len(contract.required_app_fields) != len(required_app):
        raise DeliveryContractError(f"{recipe}: duplicate App field contract")
    if set(contract.required_acceptance_fields) != required_acceptance:
        raise DeliveryContractError(f"{recipe}: incomplete acceptance field contract")
    if len(contract.required_acceptance_fields) != len(required_acceptance):
        raise DeliveryContractError(f"{recipe}: duplicate acceptance field contract")
    if set(contract.scenarios) != scenarios:
        raise DeliveryContractError(f"{recipe}: incomplete scenario contract")
    if set(contract.app_topologies) != {"standalone", "multi-service"}:
        raise DeliveryContractError(f"{recipe}: incomplete App topology contract")
    if len(contract.app_topologies) != 2:
        raise DeliveryContractError(f"{recipe}: duplicate App topology contract")
    if set(contract.acceptance_topologies) != {
        "reference-product", "declared-app", "consumer-contract",
    }:
        raise DeliveryContractError(f"{recipe}: incomplete acceptance topology contract")
    required_state = {
        "name", "use_case", "transaction_boundary", "tenant_isolation",
        "repository_seam", "migration", "verification",
    }
    if set(contract.state_required_fields) != required_state:
        raise DeliveryContractError(f"{recipe}: incomplete durable-state field contract")
    if len(contract.state_required_fields) != len(required_state):
        raise DeliveryContractError(f"{recipe}: duplicate durable-state field contract")
    if not contract.artifact_templates or any(
        not isinstance(key, str)
        or SAFE_KEY.fullmatch(key) is None
        or not isinstance(value, str)
        or not value.strip()
        for key, value in contract.artifact_templates.items()
    ):
        raise DeliveryContractError(f"{recipe}: invalid artifact templates")
    for name, scenario in contract.scenarios.items():
        if not isinstance(scenario, dict):
            raise DeliveryContractError(f"{recipe}: invalid scenario {name}")
        for field in ("intent", "participant_roles", "provider_adapter", "artifact_roles"):
            if field not in scenario:
                raise DeliveryContractError(f"{recipe}: scenario {name} omits {field}")
        if not isinstance(scenario["intent"], str) or not scenario["intent"].strip():
            raise DeliveryContractError(f"{recipe}: scenario {name} has no intent")
        if scenario["provider_adapter"] not in {"required", "forbidden"}:
            raise DeliveryContractError(f"{recipe}: invalid adapter policy for {name}")
        participant_roles = tuple(scenario["participant_roles"])
        if (
            not participant_roles
            or len(participant_roles) != len(set(participant_roles))
            or any(
                not isinstance(role, str) or SAFE_KEY.fullmatch(role) is None
                for role in participant_roles
            )
        ):
            raise DeliveryContractError(f"{recipe}: invalid participant roles for {name}")
        roles = tuple(scenario["artifact_roles"])
        if len(roles) != len(set(roles)) or not roles:
            raise DeliveryContractError(f"{recipe}: invalid artifact roles for {name}")
        if any(role not in contract.artifact_templates for role in roles):
            raise DeliveryContractError(f"{recipe}: unknown artifact role for {name}")
    if (
        not contract.state_artifact_roles
        or len(contract.state_artifact_roles) != len(set(contract.state_artifact_roles))
        or any(role not in contract.artifact_templates for role in contract.state_artifact_roles)
    ):
        raise DeliveryContractError(f"{recipe}: invalid durable-state artifact roles")
    return contract


def _participants(promise: SupportPromise, required_roles: tuple[str, ...]) -> tuple[str, ...]:
    if not promise.participants:
        raise ValueError(f"support promise {promise.key} requires participants")
    pairs: list[tuple[str, str]] = []
    for participant in promise.participants:
        role = _key(participant.role, f"support promise {promise.key} participant role")
        name = _label(participant.name, f"support promise {promise.key} participant")
        pairs.append((role, name))
    if len(pairs) != len(set(pairs)):
        raise ValueError(f"support promise {promise.key} contains duplicate participants")
    actual_roles = {role for role, _name in pairs}
    if actual_roles != set(required_roles):
        raise ValueError(
            f"support promise {promise.key} participant roles must be {sorted(required_roles)}"
        )
    return tuple(name for _role, name in pairs)


def business_service_plan(
    description: BusinessServiceDescription,
    contract: BusinessServiceContract,
) -> BusinessServicePlan:
    name = _key(description.name, "business service name")
    _label(description.capability, "business service capability")
    _label(description.owner, "business service owner")
    use_cases = _string_tuple(description.use_cases, "use cases", required=True)
    dependency_pairs: list[tuple[str, str]] = []
    for dependency in description.dependencies:
        blocked = _label(dependency.blocked, "blocked use case")
        blocker = _label(dependency.blocker, "blocking use case")
        if blocked not in use_cases or blocker not in use_cases:
            raise ValueError("use-case dependency references an unknown use case")
        if blocked == blocker:
            raise ValueError("use-case dependency cannot block itself")
        dependency_pairs.append((blocked, blocker))
    if len(dependency_pairs) != len(set(dependency_pairs)):
        raise ValueError("use-case dependencies contain duplicates")
    predecessors = {
        use_case: {blocker for blocked, blocker in dependency_pairs if blocked == use_case}
        for use_case in use_cases
    }
    resolved: set[str] = set()
    while len(resolved) < len(use_cases):
        ready = {
            use_case for use_case in use_cases
            if use_case not in resolved and predecessors[use_case].issubset(resolved)
        }
        if not ready:
            raise ValueError("use-case dependencies contain a cycle")
        resolved.update(ready)
    decisions = _string_tuple(description.decisions, "decisions", required=True)
    exclusions = _string_tuple(description.exclusions, "exclusions", required=True)
    if not description.support_promises:
        raise ValueError("business service requires support promises")

    promise_keys = tuple(promise.key for promise in description.support_promises)
    _string_tuple(promise_keys, "support promise keys", required=True)
    for key in promise_keys:
        _key(key, "support promise key")
    app_names = tuple(app.name for app in description.apps)
    _string_tuple(app_names, "App names", required=True)
    for app in description.apps:
        _key(app.name, "App name")
        if app.topology not in contract.app_topologies:
            raise ValueError(f"App {app.name} has unsupported topology")
        promises = _string_tuple(
            app.support_promises, f"App {app.name} support promises", required=True
        )
        if not set(promises).issubset(promise_keys):
            raise ValueError(f"App {app.name} references an unknown support promise")
        _label(app.verification, f"App {app.name} verification")

    acceptance_names = tuple(item.name for item in description.acceptance_consumers)
    _string_tuple(acceptance_names, "acceptance consumers", required=True)
    accepted_promises: list[str] = []
    for acceptance in description.acceptance_consumers:
        _label(acceptance.name, "acceptance consumer")
        if acceptance.topology not in contract.acceptance_topologies:
            raise ValueError(f"acceptance consumer {acceptance.name} has unsupported topology")
        if acceptance.app not in app_names:
            raise ValueError(f"acceptance consumer {acceptance.name} references an unknown App")
        promises = _string_tuple(
            acceptance.support_promises,
            f"acceptance consumer {acceptance.name} promises",
            required=True,
        )
        if not set(promises).issubset(promise_keys):
            raise ValueError(f"acceptance consumer {acceptance.name} references an unknown promise")
        accepted_promises.extend(promises)
        _label(acceptance.verification, f"acceptance consumer {acceptance.name} verification")
    if sorted(accepted_promises) != sorted(promise_keys):
        raise ValueError("every support promise requires exactly one acceptance consumer")

    artifacts_by_target: dict[str, dict[str, set[str]]] = {}
    ticket_targets: dict[str, set[str]] = {use_case: set() for use_case in use_cases}
    support_claims: list[str] = []
    promise_by_key: dict[str, SupportPromise] = {}

    def trace(target: str, consumers: tuple[str, ...], use_case: str, promise: str) -> None:
        _label(target, "planned artifact target")
        entry = artifacts_by_target.setdefault(
            target, {"consumers": set(), "use_cases": set(), "promises": set()}
        )
        entry["consumers"].update(consumers)
        entry["use_cases"].add(use_case)
        entry["promises"].add(promise)
        ticket_targets[use_case].add(target)

    for promise in description.support_promises:
        key = _key(promise.key, "support promise key")
        if promise.kind not in contract.scenarios:
            raise ValueError(f"support promise {key} has unsupported kind")
        if promise.use_case not in use_cases:
            raise ValueError(f"support promise {key} references an unknown use case")
        _label(promise.permission, f"support promise {key} permission")
        _label(promise.verification, f"support promise {key} verification")
        if promise.app not in app_names:
            raise ValueError(f"support promise {key} references an unknown App")
        scenario = contract.scenarios[promise.kind]
        consumers = _participants(promise, tuple(scenario["participant_roles"]))
        adapter_policy = scenario["provider_adapter"]
        if adapter_policy == "required":
            if promise.provider_adapter is None:
                raise ValueError(f"support promise {key} requires a provider adapter")
            _label(promise.provider_adapter, f"support promise {key} provider adapter")
        elif promise.provider_adapter is not None:
            raise ValueError(f"support promise {key} forbids a provider adapter")
        promise_by_key[key] = promise
        support_claims.append(
            f"{key}: {promise.kind} supports {', '.join(consumers)} for "
            f"{promise.use_case} in {promise.app}"
        )
        values = {
            "service": name,
            "promise": key,
            "app": promise.app,
            "provider_adapter": promise.provider_adapter or "",
        }
        for role in scenario["artifact_roles"]:
            target = contract.artifact_templates[role].format(**values)
            trace(target, consumers, promise.use_case, key)

    state_names = tuple(state.name for state in description.durable_state)
    _string_tuple(state_names, "durable state names")
    for state in description.durable_state:
        _key(state.name, "durable state name")
        if state.use_case not in use_cases:
            raise ValueError(f"durable state {state.name} references an unknown use case")
        for field in contract.state_required_fields:
            _label(getattr(state, field), f"durable state {state.name} {field}")
        related = tuple(
            promise for promise in description.support_promises
            if promise.use_case == state.use_case
        )
        consumers = tuple(sorted({
            participant.name for promise in related for participant in promise.participants
        }))
        for role in contract.state_artifact_roles:
            target = contract.artifact_templates[role].format(
                service=name, state=state.name
            )
            for promise in related:
                trace(target, consumers, state.use_case, promise.key)

    for app in description.apps:
        assigned = {promise.key for promise in description.support_promises if promise.app == app.name}
        if assigned != set(app.support_promises):
            raise ValueError(f"App {app.name} support promises do not match promise assignments")

    for acceptance in description.acceptance_consumers:
        template = contract.acceptance_topologies[acceptance.topology]
        target = template.format(consumer=acceptance.name, app=acceptance.app)
        for promise_key in acceptance.support_promises:
            promise = promise_by_key[promise_key]
            consumers = tuple(participant.name for participant in promise.participants)
            trace(target, consumers, promise.use_case, promise_key)

    artifacts = tuple(
        PlannedArtifact(
            target,
            tuple(sorted(trace_data["consumers"])),
            tuple(sorted(trace_data["use_cases"])),
            tuple(sorted(trace_data["promises"])),
        )
        for target, trace_data in sorted(artifacts_by_target.items())
    )
    if any(
        not artifact.consumers or not artifact.use_cases or not artifact.support_promises
        for artifact in artifacts
    ):
        raise ValueError("every planned artifact requires consumer/use-case traceability")

    tickets: list[BusinessServiceTicket] = []
    ticket_key_by_use_case = {
        use_case: f"use-case-{index:02d}"
        for index, use_case in enumerate(use_cases, start=1)
    }
    for use_case in use_cases:
        related_promises = tuple(
            promise for promise in description.support_promises
            if promise.use_case == use_case
        )
        related_states = tuple(
            state for state in description.durable_state if state.use_case == use_case
        )
        related_apps = tuple(
            app for app in description.apps
            if set(app.support_promises).intersection(promise.key for promise in related_promises)
        )
        related_acceptance = tuple(
            acceptance for acceptance in description.acceptance_consumers
            if set(acceptance.support_promises).intersection(
                promise.key for promise in related_promises
            )
        )
        ticket_key = ticket_key_by_use_case[use_case]
        claims = tuple(
            claim for claim in support_claims
            if any(claim.startswith(promise.key + ":") for promise in related_promises)
        )
        criteria = tuple(
            [
                f"{promise.key} enforces {promise.permission} and fulfills "
                f"{promise.verification} in {promise.app}"
                for promise in related_promises
            ]
            + [
                f"{state.name} is atomic at {state.transaction_boundary}, isolates "
                f"{state.tenant_isolation}, and migrates through {state.migration}"
                for state in related_states
            ]
            + [
                f"{acceptance.name} accepts {', '.join(acceptance.support_promises)} "
                f"through {acceptance.topology}"
                for acceptance in related_acceptance
            ]
        )
        tests = tuple(dict.fromkeys(
            [promise.verification for promise in related_promises]
            + [state.verification for state in related_states]
            + [app.verification for app in related_apps]
            + [acceptance.verification for acceptance in related_acceptance]
        ))
        if not claims or not criteria or not tests:
            raise ValueError(f"use case {use_case} lacks complete vertical evidence")
        tickets.append(BusinessServiceTicket(
            key=ticket_key,
            outcome=f"Deliver {use_case} for its named consumers and supported topology",
            blocked_by=tuple(sorted(
                ticket_key_by_use_case[blocker]
                for blocker in predecessors[use_case]
            )),
            support_claims=claims,
            acceptance_criteria=criteria,
            test_seams=tests,
            decisions=decisions,
            exclusions=exclusions,
            artifacts=tuple(sorted(ticket_targets[use_case])),
        ))

    dependency_edges = sum(len(ticket.blocked_by) for ticket in tickets)
    return BusinessServicePlan(
        spec_sections=(
            "capability, ownership, and use cases",
            "consumer and support promises",
            "permissions and contracts",
            "application, domain, and durable state",
            "adapters, app assemblies, and acceptance",
            "decisions, exclusions, and test seams",
        ),
        support_claims=tuple(support_claims),
        decisions=decisions,
        exclusions=exclusions,
        tickets=tuple(tickets),
        artifacts=artifacts,
        implementation_shape=ImplementationShape(
            ticket_count=len(tickets),
            dependency_edges=dependency_edges,
        ),
    )


def final_acceptance_status(evidence: AcceptanceInput) -> str:
    complete = (
        evidence.gate_exit == 0
        and bool(evidence.gate_base)
        and bool(evidence.gate_head)
        and bool(evidence.gate_ref)
        and bool(evidence.gate_profile)
        and bool(evidence.delivered_base)
        and bool(evidence.delivered_head)
        and bool(evidence.delivered_ref)
        and bool(evidence.delivered_profile)
        and evidence.gate_base == evidence.delivered_base
        and evidence.gate_head == evidence.delivered_head
        and evidence.gate_ref == evidence.delivered_ref
        and evidence.gate_profile == evidence.delivered_profile
        and bool(evidence.reviewed_base)
        and bool(evidence.reviewed_head)
        and evidence.reviewed_base == evidence.delivered_base
        and evidence.reviewed_head == evidence.delivered_head
        and evidence.standards_review_complete
        and evidence.spec_review_complete
        and evidence.unresolved_findings == 0
        and evidence.missing_criteria == 0
        and evidence.missing_scenarios == 0
        and evidence.evidence_exists
        and evidence.checkout_clean
    )
    return "PASS" if complete else "FAIL"


def representative_business_service_description() -> BusinessServiceDescription:
    return BusinessServiceDescription(
        name="returns",
        capability="Decide returns and publish their outcome",
        owner="Returns Team",
        use_cases=("Decide Return", "Publish Return Outcome"),
        dependencies=(UseCaseDependency("Publish Return Outcome", "Decide Return"),),
        support_promises=(
            SupportPromise(
                "fulfillment-local",
                "local-direct",
                "Decide Return",
                (Participant("caller", "Fulfillment"),),
                "returns decide",
                "returns-app",
                "local API contract test",
            ),
            SupportPromise(
                "support-remote",
                "remote-direct",
                "Decide Return",
                (Participant("caller", "Customer Support"),),
                "returns decide",
                "returns-app",
                "remote contract test",
                "returns HTTP provider adapter",
            ),
            SupportPromise(
                "shopper-http",
                "http",
                "Decide Return",
                (Participant("caller", "Shopper"),),
                "returns submit",
                "returns-app",
                "HTTP adapter contract test",
                "returns HTTP inbound adapter",
            ),
            SupportPromise(
                "warehouse-command",
                "message-command",
                "Decide Return",
                (
                    Participant("producer", "Warehouse"),
                    Participant("consumer", "Returns"),
                ),
                "returns inspect",
                "fulfillment-app",
                "command identity and inbound contract test",
                "returns message inbound adapter",
            ),
            SupportPromise(
                "policy-internal",
                "internal",
                "Decide Return",
                (Participant("caller", "Returns Policy"),),
                "returns evaluate",
                "returns-app",
                "Application seam test",
            ),
            SupportPromise(
                "accepted-event",
                "message-event",
                "Publish Return Outcome",
                (
                    Participant("producer", "Returns"),
                    Participant("consumer", "Finance"),
                ),
                "returns outcome read",
                "fulfillment-app",
                "event identity and publication contract test",
                "returns message outbound adapter",
            ),
        ),
        apps=(
            AppSupport(
                "returns-app",
                "standalone",
                (
                    "fulfillment-local",
                    "support-remote",
                    "shopper-http",
                    "policy-internal",
                ),
                "returns App startup test",
            ),
            AppSupport(
                "fulfillment-app",
                "multi-service",
                ("warehouse-command", "accepted-event"),
                "fulfillment App composition test",
            ),
        ),
        acceptance_consumers=(
            AcceptanceConsumer(
                "Shopper",
                "reference-product",
                "returns-app",
                ("shopper-http",),
                "reference product returns scenario",
            ),
            AcceptanceConsumer(
                "Returns Operators",
                "declared-app",
                "returns-app",
                ("fulfillment-local", "support-remote", "policy-internal"),
                "returns App acceptance scenario",
            ),
            AcceptanceConsumer(
                "Fulfillment Partners",
                "consumer-contract",
                "fulfillment-app",
                ("warehouse-command", "accepted-event"),
                "partner message contract scenario",
            ),
        ),
        durable_state=(
            DurableStateNeed(
                "return-ledger",
                "Decide Return",
                "one return decision transaction",
                "tenant scoped return records",
                "Return Repository",
                "returns schema history",
                "real PostgreSQL adapter test",
            ),
        ),
        decisions=(
            "Returns owns contracts and durable state",
            "Commands and events are versioned provider records",
        ),
        exclusions=(
            "No code generator or copied service template",
            "No unsupported App assembly",
        ),
    )


def representative_forward_failures(root: Path) -> list[str]:
    failures: list[str] = []
    routes = parse_routes(root / "docs/agents/delivery-workflow.md")
    route = implementation_route(ImplementationShape(
        ticket_count=1,
        later_independent_reviews=True,
    ))
    if route != ImplementationRoute("implement", "implementation-evidence"):
        failures.append(f"single-ticket forward route is invalid: {route}")
    if routes.get("one-ticket") != route.skill:
        failures.append("executable single-ticket route differs from workflow")

    accepted = AcceptanceInput(
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
    if final_acceptance_status(accepted) != "PASS":
        failures.append("complete representative evidence did not reach PASS")
    if final_acceptance_status(
        replace(accepted, standards_review_complete=False)
    ) != "FAIL":
        failures.append("forward run did not stop for an incomplete review axis")

    try:
        contract = load_business_service_contract(
            root / "docs/agents/new-business-service.md"
        )
        service_plan = business_service_plan(
            representative_business_service_description(), contract
        )
    except (DeliveryContractError, ValueError, KeyError) as error:
        failures.append(f"representative business-service shape failed: {error}")
        return failures
    service_targets = {artifact.target for artifact in service_plan.artifacts}
    expected_service_targets = {
        "services/returns/returns-api",
        "services/returns/returns-service",
        "returns HTTP inbound adapter",
        "returns HTTP provider adapter",
        "returns message inbound adapter",
        "returns message outbound adapter",
        "returns persistence outbound adapter for return-ledger",
        "returns service-owned migration for return-ledger",
        "real-database verification for return-ledger",
        "returns-app assembly",
        "fulfillment-app assembly",
        "reference-product acceptance entry for Shopper",
        "returns-app acceptance entry for Returns Operators",
        "consumer contract acceptance for Fulfillment Partners",
    }
    if not expected_service_targets.issubset(service_targets):
        failures.append("representative business-service plan is incomplete")
    if "returns synchronous Service API" not in service_targets:
        failures.append("direct support does not publish a synchronous Service API")
    async_only = business_service_plan(
        replace(
            representative_business_service_description(),
            use_cases=("Decide Return",),
            dependencies=(),
            support_promises=(
                representative_business_service_description().support_promises[3],
            ),
            apps=(AppSupport(
                "fulfillment-app",
                "multi-service",
                ("warehouse-command",),
                "fulfillment App composition test",
            ),),
            acceptance_consumers=(AcceptanceConsumer(
                "Fulfillment Partners",
                "consumer-contract",
                "fulfillment-app",
                ("warehouse-command",),
                "partner message contract scenario",
            ),),
            durable_state=(),
        ),
        contract,
    )
    if "reconciliation synchronous Service API" in {
        artifact.target for artifact in async_only.artifacts
    } or any("synchronous Service API" in target for target in {
        artifact.target for artifact in async_only.artifacts
    }):
        failures.append("asynchronous-only support created a synchronous Service API")
    internal_promise = representative_business_service_description().support_promises[4]
    internal_only = business_service_plan(
        replace(
            representative_business_service_description(),
            use_cases=("Decide Return",),
            dependencies=(),
            support_promises=(internal_promise,),
            apps=(AppSupport(
                "returns-app", "standalone", ("policy-internal",),
                "returns App startup test",
            ),),
            acceptance_consumers=(AcceptanceConsumer(
                "Returns Operators", "declared-app", "returns-app",
                ("policy-internal",), "returns App acceptance scenario",
            ),),
            durable_state=(),
        ),
        contract,
    )
    internal_targets = {artifact.target for artifact in internal_only.artifacts}
    if any(target.endswith("-api") for target in internal_targets):
        failures.append("internal-only support created an unconsumed API module")
    if any("adapter" in target.lower() for target in internal_targets):
        failures.append("internal-only support created an unconsumed Adapter")
    if any(
        not artifact.consumers
        or not artifact.use_cases
        or not artifact.support_promises
        for artifact in service_plan.artifacts
    ):
        failures.append("business-service plan contains an unexplained artifact")
    for ticket in service_plan.tickets:
        if not all((
            ticket.support_claims,
            ticket.acceptance_criteria,
            ticket.test_seams,
            ticket.decisions,
            ticket.exclusions,
            ticket.artifacts,
        )):
            failures.append(f"business-service vertical ticket is incomplete: {ticket.key}")
    return failures


def parse_routes(workflow: Path) -> dict[str, str]:
    routes: dict[str, str] = {}
    for line in workflow.read_text(encoding="utf-8").splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 3 or not cells[0].startswith("`"):
            continue
        shape = cells[0].strip("`")
        match = re.search(r"\[`([^`]+)`\]", cells[1])
        if match:
            routes[shape] = match.group(1)
    return routes


def _frontmatter(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "---":
        raise DeliveryContractError(f"{path}: YAML frontmatter is required")
    try:
        end = lines.index("---", 1)
    except ValueError as error:
        raise DeliveryContractError(f"{path}: unterminated YAML frontmatter") from error
    values: dict[str, str] = {}
    for line in lines[1:end]:
        if ":" in line and not line.startswith((" ", "\t")):
            key, value = line.split(":", 1)
            values[key.strip()] = value.strip().strip('"')
    return values


def _repository_markdown(root: Path) -> list[Path]:
    files = [root / "AGENTS.md", root / "docs/agents/delivery-workflow.md"]
    files.append(root / "docs/agents/delivery-standards.md")
    files.append(root / "docs/agents/new-business-service.md")
    files.extend(sorted((root / ".agents/skills").glob("**/*.md")))
    return files


def _check_links(root: Path, source: Path, failures: list[str]) -> None:
    content = source.read_text(encoding="utf-8")
    for raw_target in MARKDOWN_LINK.findall(content):
        target = raw_target.split("#", 1)[0].strip()
        if not target or "://" in target or target.startswith("mailto:"):
            continue
        resolved = (source.parent / target).resolve()
        try:
            resolved.relative_to(root.resolve())
        except ValueError:
            failures.append(f"{source}: link escapes repository: {raw_target}")
            continue
        if not resolved.is_file():
            failures.append(f"{source}: missing link target: {raw_target}")


def validate_repository(root: Path) -> list[str]:
    root = root.resolve()
    failures: list[str] = []
    skills_root = root / ".agents/skills"
    workflow = root / "docs/agents/delivery-workflow.md"
    standards = root / "docs/agents/delivery-standards.md"
    service_recipe = root / "docs/agents/new-business-service.md"

    for required in (root / "AGENTS.md", workflow, standards, service_recipe):
        if not required.is_file():
            failures.append(f"missing repository delivery entry: {required}")
    if failures:
        return failures

    agents_text = (root / "AGENTS.md").read_text(encoding="utf-8")
    if "docs/agents/delivery-workflow.md" not in agents_text:
        failures.append("AGENTS.md does not link the repository delivery workflow")
    if "docs/agents/new-business-service.md" not in agents_text:
        failures.append("AGENTS.md does not link the new Business Service recipe")
    if ".agents/skills/add-business-service/SKILL.md" not in agents_text:
        failures.append("AGENTS.md does not link the add-business-service Skill")

    workflow_text = workflow.read_text(encoding="utf-8")
    if "add-business-service" not in workflow_text:
        failures.append("delivery workflow does not link the add-business-service entry")

    service_skill = skills_root / "add-business-service/SKILL.md"
    service_skill_text = (
        service_skill.read_text(encoding="utf-8") if service_skill.is_file() else ""
    )
    if "../../../docs/agents/new-business-service.md" not in service_skill_text:
        failures.append("add-business-service Skill does not read the Shape Contract recipe")
    if "Route by task shape" not in service_skill_text:
        failures.append("add-business-service Skill does not route through the workflow table")
    copied_shape_keys = [
        key for key in EXPECTED_ROUTES if f"`{key}`" in service_skill_text
    ]
    if copied_shape_keys:
        failures.append(
            "add-business-service Skill copies workflow shape keys: "
            f"{copied_shape_keys}"
        )

    actual_skills = (
        {path.name for path in skills_root.iterdir() if path.is_dir()}
        if skills_root.is_dir()
        else set()
    )
    missing_skills = set(REQUIRED_SKILLS) - actual_skills
    if missing_skills:
        failures.append(
            "required project Skills are missing: "
            f"required={sorted(REQUIRED_SKILLS)} missing={sorted(missing_skills)}"
        )

    for path in skills_root.rglob("*") if skills_root.is_dir() else ():
        if path.is_symlink():
            failures.append(f"symbolic link is forbidden in project Skills: {path}")

    for name in sorted(actual_skills):
        skill = skills_root / name / "SKILL.md"
        if not skill.is_file() or skill.is_symlink():
            failures.append(f"missing regular project Skill: {skill}")
            continue
        try:
            frontmatter = _frontmatter(skill)
        except DeliveryContractError as error:
            failures.append(str(error))
            continue
        if frontmatter.get("name") != name:
            failures.append(f"{skill}: frontmatter name must be {name}")
        if not frontmatter.get("description"):
            failures.append(f"{skill}: frontmatter description is required")
        if "## Completion" not in skill.read_text(encoding="utf-8"):
            failures.append(f"{skill}: explicit Completion boundary is required")

    for relative in DECLARED_DEPENDENCY_ROOTS:
        dependency = root / relative
        if not dependency.exists():
            failures.append(f"missing declared delivery dependency: {dependency}")
        elif dependency.is_symlink():
            failures.append(f"declared delivery dependency must not be a symlink: {dependency}")

    routes = parse_routes(workflow)
    if routes != EXPECTED_ROUTES:
        failures.append(f"delivery routing differs: expected={EXPECTED_ROUTES} actual={routes}")

    standards_text = standards.read_text(encoding="utf-8")
    for authority in (
        "create commits",
        "create or remove branches and worktrees",
        "push, publish, deploy",
        "update or close tracker records",
    ):
        if authority not in standards_text:
            failures.append(f"delivery standards omit explicit authority: {authority}")
    if "user explicitly invokes it" not in standards_text:
        failures.append("delivery standards must not load TDD unconditionally")
    worker_contract = (
        skills_root / "implement-frontier/references/worker-contract.md"
    )
    worker_contract_text = (
        worker_contract.read_text(encoding="utf-8")
        if worker_contract.is_file()
        else ""
    )
    if "only when explicitly invoked" not in worker_contract_text:
        failures.append("frontier worker contract must keep TDD risk-selected")

    for markdown in _repository_markdown(root):
        if not markdown.is_file():
            continue
        content = markdown.read_text(encoding="utf-8")
        if PERSONAL_REFERENCE.search(content):
            failures.append(f"{markdown}: personal Skill path or username is forbidden")
        _check_links(root, markdown, failures)

    for executable in (
        root / "tools/agent-delivery/validate.py",
        root / "tools/agent-delivery/test/run-tests.sh",
        root / "tools/quality/quality-gate.sh",
        root / "tools/quality/quality_gate.py",
        root / "tools/quality/test/run-tests.sh",
        root / "tools/quality/checks.d/common/10-agent-delivery",
    ):
        if not executable.is_file() or executable.is_symlink():
            failures.append(f"missing regular delivery executable: {executable}")
        elif executable.stat().st_mode & 0o111 == 0:
            failures.append(f"delivery executable is not executable: {executable}")

    return failures
