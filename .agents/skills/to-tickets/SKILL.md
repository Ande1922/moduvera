---
name: to-tickets
description: Produce one or more independently verifiable Moduvera tickets from an approved spec when ticketing is selected, with explicit blockers and tracker-valid acceptance criteria.
---

# To Tickets

Use [route by task shape](../../../docs/agents/delivery-workflow.md#route-by-task-shape)
to choose whether the spec needs tickets. An explicitly requested ticket remains
a valid tracking choice for a spec that could otherwise be implemented directly.

## Process

1. Use the complete approved spec and
   [task context rules](../../../docs/agents/domain.md#task-context),
   [tracker conventions](../../../docs/agents/issue-tracker.md), and
   [status vocabulary](../../../docs/agents/triage-labels.md).
2. Draft narrow vertical slices. Each ticket must deliver observable behavior,
   fit one fresh implementation context, remain verifiable at its declared
   base, and name genuine blockers. One complete bounded slice is a valid
   one-ticket result. Use expand–migrate–contract only when a wide mechanical
   refactor cannot stay green as vertical slices.
3. Classify each slice using [task complexity](../../../docs/agents/task-complexity.md).
   Present titles, complexity and basis, blockers, behavior, acceptance criteria,
   and verification seams. Reuse approved granularity and edges; resolve only
   remaining decisions with the user before publishing.
4. When tracker writes are authorized, publish one file per ticket under
   `.scratch/<feature-slug>/issues/NN-<slug>.md` with `Type: issue`,
   `Status: ready-for-agent`, `Complexity:`, `Complexity basis:`, `Blocked by:`,
   outcome, acceptance criteria, verification, and exclusions. Never modify or
   close a parent implicitly.

## Completion

Stop when the approved DAG is published or, without tracker-write authority,
returned as a proposed DAG. Report the current frontier and unresolved
dependencies. Do not begin implementation.

Follow the shared [authorization boundary](../../../docs/agents/delivery-standards.md#authorization-boundary).
