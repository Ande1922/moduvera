---
name: implement-frontier
description: Coordinate several dependency-linked Moduvera tickets as authorized ready-frontier waves with isolated writers, independent dual-axis review, and dependency-ordered integration.
---

# Implement Frontier

Use this outer workflow for several tickets with blockers, ready-frontier
waves, one worktree per writer, independent review contexts, or ordered local
integration. Use [`implement`](../implement/SKILL.md) for one bounded change.

## Roles

- The coordinator owns graph validation, authorization, scheduling, worktree
  preparation, review aggregation, integration, cross-ticket checks, and the
  execution ledger. It does not edit ticket code while writers are active.
- A ticket worker owns exactly one ticket and worktree, creates no agents, and
  returns the evidence required by
  [the worker contract](references/worker-contract.md).
- Standards and Spec reviewers independently inspect the same committed range
  read-only. The original worker fixes only coordinator-accepted findings in
  the same worktree with an ordinary follow-up commit.

If isolated worktrees or independent review contexts are unavailable, stop and
report that the requested frontier guarantee cannot be provided. Do not
simulate independence in one mutable checkout.

## Process

1. Read every ticket and blocker. Pin each base, verify code-producing blocker
   commits are ancestors, reject missing references/cycles, and compute the
   open frontier.
2. Classify frontier tickets as parallel-safe, order-sensitive, or coupled.
   Add an edge for a clear shared first writer; stop for ticket reshaping when
   coupled tickets cannot remain independently green.
3. Present the wave, worktrees/branches, likely touch areas, checks, conflicts,
   and required operations. Obtain one explicit authorization for the exact
   mutations needed; cleanup remains separate.
4. Create isolated writers only after authorization. Read the
   [worker contract](references/worker-contract.md) and give each writer a
   focused brief with repository paths rather than coordinator transcripts.
5. Require a current commit and exact ticket verification. Run both review
   axes against the pinned base, aggregate without merging their judgments,
   return accepted findings to the same worker, and rerun affected checks.
6. When integration is authorized, integrate reviewed ticket commits in
   topological order. A conflict stops integration for direction. Run focused
   cross-ticket checks after each wave and the required repository suite after
   the authorized frontier is integrated.
7. Update tracker records only when that separate capability was granted, then
   recompute the frontier from repository/tracker state.

## Completion

Stop at the authorized frontier or first unresolved blocker. Report every
ticket's base, branch, worktree, commit, checks, two-axis review disposition,
integration state, remaining frontier, and worktrees left in place. An
implemented but unintegrated or unverified ticket is not complete.

All implementation follows the shared
[test, review, and authorization standards](../../../docs/agents/delivery-standards.md).
