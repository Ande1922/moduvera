---
name: to-tickets
description: Split an approved Moduvera spec into independently verifiable tracer-bullet tickets with explicit blockers and tracker-valid acceptance criteria.
---

# To Tickets

## Process

1. Read the approved spec in full, plus `AGENTS.md`, applicable ADRs,
   [tracker conventions](../../../docs/agents/issue-tracker.md), and
   [status vocabulary](../../../docs/agents/triage-labels.md).
2. Draft narrow vertical slices. Each ticket must deliver observable behavior,
   fit one fresh implementation context, remain verifiable at its declared
   base, and name genuine blockers. Use expand–migrate–contract only when a
   wide mechanical refactor cannot stay green as vertical slices.
3. Present titles, blockers, behavior, acceptance criteria, and verification
   seams. Iterate until the user approves granularity and edges.
4. When tracker writes are authorized, publish one file per ticket under
   `.scratch/<feature-slug>/issues/NN-<slug>.md` with `Type: issue`,
   `Status: ready-for-agent`, `Blocked by:`, outcome, acceptance criteria,
   verification, and exclusions. Never modify or close a parent implicitly.

## Completion

Stop when the approved DAG is published or, without tracker-write authority,
returned as a proposed DAG. Report the current frontier and unresolved
dependencies. Do not begin implementation.

Follow the shared [authorization boundary](../../../docs/agents/delivery-standards.md#authorization-boundary).
