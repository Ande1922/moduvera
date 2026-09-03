"""Deterministic checks for the repository-owned agent delivery contract."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re


REQUIRED_SKILLS = (
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
    r"(?:/Users/|/home/[^/\s]+/|~/\.(?:codex|agents)(?:/|\b)|"
    r"\$HOME/\.(?:codex|agents)(?:/|\b))",
    re.IGNORECASE,
)


class DeliveryContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class AcceptanceInput:
    gate_exit: int
    gate_head: str
    delivered_head: str
    unresolved_findings: int
    missing_criteria: int
    missing_scenarios: int
    evidence_exists: bool


def final_acceptance_status(evidence: AcceptanceInput) -> str:
    complete = (
        evidence.gate_exit == 0
        and bool(evidence.gate_head)
        and evidence.gate_head == evidence.delivered_head
        and evidence.unresolved_findings == 0
        and evidence.missing_criteria == 0
        and evidence.missing_scenarios == 0
        and evidence.evidence_exists
    )
    return "PASS" if complete else "FAIL"


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

    for required in (root / "AGENTS.md", workflow, standards):
        if not required.is_file():
            failures.append(f"missing repository delivery entry: {required}")
    if failures:
        return failures

    agents_text = (root / "AGENTS.md").read_text(encoding="utf-8")
    if "docs/agents/delivery-workflow.md" not in agents_text:
        failures.append("AGENTS.md does not link the repository delivery workflow")

    actual_skills = (
        {path.name for path in skills_root.iterdir() if path.is_dir()}
        if skills_root.is_dir()
        else set()
    )
    if actual_skills != set(REQUIRED_SKILLS):
        failures.append(
            "project Skill set differs: "
            f"expected={sorted(REQUIRED_SKILLS)} actual={sorted(actual_skills)}"
        )

    for path in skills_root.rglob("*") if skills_root.is_dir() else ():
        if path.is_symlink():
            failures.append(f"symbolic link is forbidden in project Skills: {path}")

    for name in REQUIRED_SKILLS:
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
        root / "tools/agent-delivery/test/run-tests.sh",
        root / "tools/quality/checks.d/common/10-agent-delivery",
    ):
        if not executable.is_file() or executable.is_symlink():
            failures.append(f"missing regular delivery executable: {executable}")
        elif executable.stat().st_mode & 0o111 == 0:
            failures.append(f"delivery executable is not executable: {executable}")

    return failures
