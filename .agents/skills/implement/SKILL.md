---
name: implement
description: Implement one bounded Moduvera change, approved spec, or ticket in the current checkout with proportional tests, a post-green cleanup check, and exact evidence.
---

# Implement

Own one implementation loop. Route to
[`implement-frontier`](../implement-frontier/SKILL.md) only when implementation
must coordinate multiple tickets, dependency edges or ready waves, isolated
ticket writers, or dependency-ordered integration. A single ticket stays here
even when independent Standards and Spec reviews will run afterward.

## Process

1. Read the request, approved spec, or ticket, use the
   [task context rules](../../../docs/agents/domain.md#task-context), and apply
   [delivery standards](../../../docs/agents/delivery-standards.md). Establish
   one outcome, acceptance criteria, fixed scope, and authorized operations.
   Confirm or assign [task complexity](../../../docs/agents/task-complexity.md)
   in the scope summary; reassess when its basis changes.
2. Inspect the smallest relevant implementation and test surface. Preserve
   unrelated changes.
3. Implement in focused slices with risk-proportionate tests. Use the project
   [`tdd`](../tdd/SKILL.md) only when explicitly invoked or locally required;
   actual bug fixes remain regression-first.
4. Run narrow checks until green. Apply the post-green cleanup check from
   delivery standards, rerun checks affected by any cleanup, and complete
   required change-level validation.
5. Review the diff once for acceptance, repository rules, scope expansion,
   generated artifacts, and secrets. Resolve material findings.
6. Commit only with explicit commit authority.

## Completion

Stop after reporting behavior, complexity and basis, changed files, exact checks/results, unresolved
risks, and the commit SHA when created. Do not start formal dual-axis review,
tracker updates, integration, push, or cleanup as an implied next step.
