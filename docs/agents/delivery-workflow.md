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
| `bounded-spec` | [`implement`](../../.agents/skills/implement/SKILL.md) | One approved, bounded spec is implemented and verified against its own criteria without a ticket. |
| `approved-spec` | [`to-tickets`](../../.agents/skills/to-tickets/SKILL.md) | When ticketing is selected below, one or more approved tickets declare acceptance criteria and genuine blocking edges. |
| `one-ticket` | [`implement`](../../.agents/skills/implement/SKILL.md) | One bounded ticket is implemented, checked for needed cleanup, narrowly verified, and reported. |
| `ticket-dag` | [`implement-frontier`](../../.agents/skills/implement-frontier/SKILL.md) | Authorized frontier waves are implemented, independently reviewed, integrated if authorized, and evidenced. |
| `fixed-diff-review` | [`code-review`](../../.agents/skills/code-review/SKILL.md) | Standards and Spec findings are reported separately against one fixed point. |
| `review-clean` | [`quality-gate`](../../.agents/skills/quality-gate/SKILL.md) | The repository gate exits and its exact evidence is reported. |
| `gate-pass` | [`final-acceptance`](../../.agents/skills/final-acceptance/SKILL.md) | Evidence is current and every applicable acceptance obligation is accounted for. |

For an approved spec, recommend the route with one brief reason based on its
[complexity](task-complexity.md), acceptance boundaries, and dependencies:

- Use `bounded-spec` when one clear outcome and its acceptance/test seams fit
  one controlled implementation context, with no need to schedule separate
  deliverables. Pass the approved spec directly to `implement`.
- Use `approved-spec` and produce one ticket when that same bounded work needs
  a separate assignment, status, or handoff record, or the user requests a ticket.
- Use `approved-spec` and produce multiple tickets for independently verifiable
  deliverables or dependencies that need separate scheduling. Implement them
  individually, or use `implement-frontier` when coordinating their execution.

Complexity informs the necessary evidence and whether a scope is manageable;
it sets no ticket quota. Resolve material design uncertainty in the spec
before selecting an implementation route. Reuse existing approvals and stage
authority; ask only about unresolved decisions that affect scope or execution.
Direct implementation retains spec acceptance criteria and all applicable
review, test, gate, and authorization obligations.

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
3. Select the direct or ticketed route above. Use `to-tickets` only when
   ticketing is selected; resolve any unapproved granularity or dependency edges.
4. Run the selected implementation stage, including narrow verification and
   its post-green cleanup check under [delivery standards](delivery-standards.md).
5. Review the committed or otherwise fixed diff with `code-review`. Return
   accepted findings to the original implementer, then review the resulting
   fixed comparison range again. In an authorized frontier, use the registered
   [ticket review loop](ticket-review-loop.md) for routine repairs and escalate
   its exceptions instead of routing every repair through the coordinator.
6. With no unresolved accepted finding, run `quality-gate` against that fixed
   base/head. Source changes after the run invalidate it.
7. Run `final-acceptance`, including applicable Scenario gates for public API,
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
- all spec and applicable ticket acceptance criteria and required tests are evidenced;
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
