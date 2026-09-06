# Repository delivery workflow

This is the authoritative composition of Moduvera's repository-owned delivery
stages. Each linked Skill can be invoked by itself and stops at its own
completion condition. Run the full chain only when the user asks for the full
delivery outcome; reaching a stage does not silently authorize the next
stage's mutations.

Read [delivery standards](delivery-standards.md) before implementation. Use
the tracker conventions in [issue-tracker.md](issue-tracker.md), the status
vocabulary in [triage-labels.md](triage-labels.md), and the repository domain
rules reached from `AGENTS.md`.

## Route by task shape

For a new Business Service, start with the project
[`add-business-service` Skill](../../.agents/skills/add-business-service/SKILL.md).
It reads the authoritative
[service-shaping recipe](new-business-service.md), accounts for consumers,
contracts, persistence, and App Assemblies, then selects one existing shape
key below. It is an intake route, not a second specification, ticket, or
implementation process.

The `Shape key` column is stable input for the repository forward test.

| Shape key | Project Skill or stage | Stop condition |
| --- | --- | --- |
| `decision-unclear` | [`grill-with-docs`](../../.agents/skills/grill-with-docs/SKILL.md) | Decisions, alternatives, unknowns, and required domain-doc updates are explicit. |
| `agreed-decision` | [`to-spec`](../../.agents/skills/to-spec/SKILL.md) | One reviewable spec records scope, seams, decisions, tests, and exclusions. |
| `approved-spec` | [`to-tickets`](../../.agents/skills/to-tickets/SKILL.md) | Approved tracer-bullet tickets declare acceptance criteria and blocking edges. |
| `one-ticket` | [`implement`](../../.agents/skills/implement/SKILL.md) | One bounded ticket is implemented, cleaned, narrowly verified, and reported. |
| `ticket-dag` | [`implement-frontier`](../../.agents/skills/implement-frontier/SKILL.md) | Authorized frontier waves are implemented, independently reviewed, integrated if authorized, and evidenced. |
| `fixed-diff-review` | [`code-review`](../../.agents/skills/code-review/SKILL.md) | Standards and Spec findings are reported separately against one fixed point. |
| `review-clean` | [`quality-gate`](../../.agents/skills/quality-gate/SKILL.md) | The repository gate exits and its exact evidence is reported. |
| `gate-pass` | [`final-acceptance`](../../.agents/skills/final-acceptance/SKILL.md) | Evidence is current and every applicable acceptance obligation is accounted for. |

Choose `implement` for exactly one bounded change or ticket in the current
checkout. A later independent Standards/Spec review remains the separate
`code-review` stage and does not turn single-ticket implementation into a
frontier run. Choose `implement-frontier` only when implementation itself must
coordinate multiple tickets, dependency edges or ready waves, isolated ticket
writers, or dependency-ordered integration. They share the same implementation
and test invariants; the frontier Skill adds scheduling rather than a second
change loop.

## Full delivery chain

For a full feature delivery, proceed in this order:

1. Resolve material product and architecture uncertainty with
   `grill-with-docs`.
2. Synthesize the agreed outcome with `to-spec` and obtain approval for its
   test seams.
3. Split the approved spec with `to-tickets` and confirm its dependency graph.
4. Select exactly one implementation route from the table above.
5. After narrow tests pass, perform Clean Code and rerun affected checks as
   required by [delivery standards](delivery-standards.md).
6. Review the committed or otherwise fixed diff with `code-review`. Return
   accepted findings to the original implementer, then review the resulting
   fixed comparison range again. In an authorized frontier, use the registered
   [ticket review loop](ticket-review-loop.md) for routine repairs and escalate
   its exceptions instead of routing every repair through the coordinator.
7. With no unresolved accepted finding, run `quality-gate` against that fixed
   base/head. Source changes after the run invalidate it.
8. Run `final-acceptance`, including applicable Scenario gates for public API,
   App Assembly, message route, migration, image, or topology changes.

Every stage returns its artifacts, exact checks, unresolved decisions, and
authorization-limited actions. Failure, missing evidence, or unavailable
capability is a stop with a truthful status, never an inferred pass.

## Application topology scope

Apply [ADR 0038](../adr/0038-retain-monolith-as-on-demand-assembly.md) when selecting Scenario gates.
The microservice Golden Path is the only default topology. Retain monolith
compilation and shared architectural contracts; require its dedicated runtime,
black-box, recovery and image qualification only when monolith support is
explicitly included in the task. New services need not join that assembly.
Report monolith runtime as outside scope for ordinary delivery, not as PASS.

## Final acceptance boundary

Final acceptance requires all of the following:

- the delivered head and comparison base match the reviewed and gated commits;
- both review axes completed against that exact base/head and have no
  unresolved accepted finding;
- the latest applicable gate exited zero and its evidence still exists;
- all ticket acceptance criteria and required tests are evidenced;
- every applicable Scenario gate passed; and
- the checkout is clean. An explicitly uncommitted diff may be reported as a
  stage artifact, but cannot receive final PASS evidence.

Final acceptance reports commit/evidence identifiers and remaining risks. It
does not commit, update trackers, integrate, push, clean worktrees, publish, or
deploy unless those operations were explicitly authorized.

The coordinator owns this acceptance decision. Deterministic receipts establish
comparison and execution facts; reviewers establish independent semantic
judgments. Use their original reports and criterion-to-evidence mapping, inspect
identified high-risk seams and cross-ticket effects, and investigate missing or
contradictory evidence. A ticket review-complete packet is an integration input,
not final delivery PASS.
