---
name: implement
description: Implement one bounded Moduvera change or one ticket in the current checkout with proportional tests, post-green cleanup, and exact evidence.
---

# Implement

Own one implementation loop. When the request contains dependency-linked
tickets, frontier waves, isolated writers, independent reviewer agents, or
dependency-ordered integration, stop before editing and route the run to
[`implement-frontier`](../implement-frontier/SKILL.md).

## Process

1. Read the request/ticket, `AGENTS.md`, relevant domain docs and ADRs, and
   [delivery standards](../../../docs/agents/delivery-standards.md). Establish
   one outcome, acceptance criteria, fixed scope, and authorized operations.
2. Inspect the smallest relevant implementation and test surface. Preserve
   unrelated changes.
3. Implement in focused slices with risk-proportionate tests. Use the project
   [`tdd`](../tdd/SKILL.md) only when explicitly invoked or locally required;
   actual bug fixes remain regression-first.
4. Run narrow checks until green. Perform the required Clean Code pass, then
   rerun affected checks and required ticket-level validation.
5. Review the diff once for acceptance, repository rules, scope expansion,
   generated artifacts, and secrets. Resolve material findings.
6. Commit only with explicit commit authority.

## Completion

Stop after reporting behavior, changed files, exact checks/results, unresolved
risks, and the commit SHA when created. Do not start formal dual-axis review,
tracker updates, integration, push, or cleanup as an implied next step.
