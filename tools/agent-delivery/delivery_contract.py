"""Deterministic checks for the repository-owned agent delivery contract."""

from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
import re


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
class BusinessServiceDescription:
    name: str
    capability: str
    owner: str
    local_direct_consumers: tuple[str, ...] = ()
    remote_direct_consumers: tuple[str, ...] = ()
    http_entry: bool = False
    asynchronous_commands: tuple[str, ...] = ()
    published_events: tuple[str, ...] = ()
    internal_use_cases: tuple[str, ...] = ()
    persistence: bool = False
    standalone_app: bool = False
    multi_service_apps: tuple[str, ...] = ()


@dataclass(frozen=True)
class PlannedArtifact:
    target: str
    required_by: str


@dataclass(frozen=True)
class BusinessServiceTicket:
    key: str
    outcome: str
    blocked_by: tuple[str, ...]
    artifacts: tuple[str, ...]


@dataclass(frozen=True)
class BusinessServicePlan:
    spec_sections: tuple[str, ...]
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


def business_service_plan(description: BusinessServiceDescription) -> BusinessServicePlan:
    for field_name in ("name", "capability", "owner"):
        if not getattr(description, field_name).strip():
            raise ValueError(f"business service {field_name} is required")
    if re.fullmatch(r"[a-z][a-z0-9-]*", description.name) is None:
        raise ValueError("business service name must be a lowercase hyphenated identifier")
    if not any((
        description.local_direct_consumers,
        description.remote_direct_consumers,
        description.http_entry,
        description.asynchronous_commands,
        description.internal_use_cases,
    )):
        raise ValueError(
            "business service requires at least one consuming entry or internal use case"
        )

    name = description.name
    artifacts: list[PlannedArtifact] = []
    ticket_artifacts: list[tuple[str, str]] = []

    def require(ticket: str, target: str, reason: str) -> None:
        artifacts.append(PlannedArtifact(target, reason))
        ticket_artifacts.append((ticket, target))

    has_messages = bool(description.asynchronous_commands or description.published_events)
    direct_consumers = (
        description.local_direct_consumers + description.remote_direct_consumers
    )
    has_provider_contracts = bool(direct_consumers or has_messages)
    if has_provider_contracts:
        contract_reasons: list[str] = []
        if direct_consumers:
            contract_reasons.append(
                "supported direct consumers: " + ", ".join(direct_consumers)
            )
        if has_messages:
            contract_reasons.append("provider-owned asynchronous message contracts")
        require(
            "contracts",
            f"services/{name}/{name}-api",
            "; ".join(contract_reasons),
        )
    if direct_consumers:
        require(
            "contracts",
            f"{name} synchronous Service API",
            "supported direct local or remote call for "
            + ", ".join(direct_consumers),
        )
    if description.remote_direct_consumers:
        require(
            "adapters",
            f"{name} remote client outbound adapter",
            "declared remote direct consumers: "
            + ", ".join(description.remote_direct_consumers),
        )

    require(
        "use-cases",
        f"services/{name}/{name}-service",
        f"{description.owner} owns the {description.capability} Application use case "
        "and any required Domain behavior",
    )

    if description.http_entry:
        require(
            "adapters",
            f"{name} HTTP inbound adapter",
            "declared HTTP entry invokes the provider-owned Application use case",
        )
    if description.asynchronous_commands:
        require(
            "adapters",
            f"{name} message inbound adapter",
            "declared asynchronous commands: "
            + ", ".join(description.asynchronous_commands),
        )
    if description.published_events:
        require(
            "adapters",
            f"{name} message outbound adapter",
            "declared published integration events: "
            + ", ".join(description.published_events),
        )
    if description.persistence:
        require(
            "persistence",
            f"{name} persistence outbound adapter",
            "declared durable business state",
        )
        require(
            "persistence",
            f"{name} service-owned migration",
            "persistence requires service-owned schema history and migration definition",
        )
    if description.standalone_app:
        require(
            "assembly",
            f"{name}-app assembly",
            "declared standalone runnable application",
        )
    for app in description.multi_service_apps:
        require(
            "assembly",
            f"{app} assembly",
            f"declared multi-service application consumer of {name}",
        )
    if has_messages:
        require(
            "verification",
            "repository message-contract verification",
            "message identity, asynchronous-only, and inbound contract evidence",
        )
    require(
        "verification",
        "repository architecture rules",
        "new API and service boundaries require repository architecture coverage",
    )
    if any((
        description.http_entry,
        direct_consumers,
        has_messages,
        description.standalone_app,
        description.multi_service_apps,
    )):
        require(
            "verification",
            "reference-product acceptance entry",
            "declared public, cross-service, message, or App Assembly support",
        )

    ordered_keys = (
        "contracts",
        "use-cases",
        "persistence",
        "adapters",
        "assembly",
        "verification",
    )
    outcomes = {
        "contracts": "Publish only the provider contracts required by named consumers",
        "use-cases": "Implement the owned Application and Domain behavior",
        "persistence": "Persist business state through service-owned migrations",
        "adapters": "Connect declared inbound and outbound protocols to the use cases",
        "assembly": "Select the service in each declared App Assembly",
        "verification": "Qualify the declared support through architecture and acceptance evidence",
    }
    tickets: list[BusinessServiceTicket] = []
    for key in ordered_keys:
        targets = tuple(target for owner, target in ticket_artifacts if owner == key)
        if not targets:
            continue
        blockers = (tickets[-1].key,) if tickets else ()
        tickets.append(BusinessServiceTicket(key, outcomes[key], blockers, targets))

    dependency_edges = sum(len(ticket.blocked_by) for ticket in tickets)
    return BusinessServicePlan(
        spec_sections=(
            "capability and ownership",
            "use cases and contracts",
            "application and domain",
            "adapters and persistence",
            "app assembly",
            "verification and exclusions",
        ),
        tickets=tuple(tickets),
        artifacts=tuple(artifacts),
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

    service_plan = business_service_plan(BusinessServiceDescription(
        name="returns",
        capability="Decide and track merchandise returns",
        owner="Returns",
        local_direct_consumers=("Fulfillment",),
        remote_direct_consumers=("Customer Support",),
        http_entry=True,
        asynchronous_commands=("InspectReturn",),
        published_events=("ReturnAccepted",),
        persistence=True,
        standalone_app=True,
        multi_service_apps=("fulfillment-app",),
    ))
    service_targets = {artifact.target for artifact in service_plan.artifacts}
    expected_service_targets = {
        "services/returns/returns-api",
        "services/returns/returns-service",
        "returns HTTP inbound adapter",
        "returns message inbound adapter",
        "returns message outbound adapter",
        "returns remote client outbound adapter",
        "returns persistence outbound adapter",
        "returns service-owned migration",
        "returns-app assembly",
        "fulfillment-app assembly",
        "repository message-contract verification",
        "repository architecture rules",
        "reference-product acceptance entry",
    }
    if not expected_service_targets.issubset(service_targets):
        failures.append("representative business-service plan is incomplete")
    if any(not artifact.required_by.strip() for artifact in service_plan.artifacts):
        failures.append("business-service plan contains an unexplained artifact")
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
