---
name: implement-frontier
description: Coordinate several dependency-linked Moduvera tickets as authorized ready-frontier waves with isolated writers, independent dual-axis review, and dependency-ordered integration.
---

# Implement Frontier

Use this outer workflow when implementation must coordinate multiple tickets,
blocker edges or ready-frontier waves, isolated ticket writers, or ordered
local integration. Use [`implement`](../implement/SKILL.md) for one bounded
change; later independent review is the separate `code-review` stage, not a
frontier trigger.

## Roles

- The coordinator owns scope, authorization, dependency decisions, initial
  role assignment, exceptions, cross-ticket assessment, and final acceptance.
  Use repository scripts for checkout checks and receipts; run authorized
  integration and validation from their existing entrypoints. It does not edit
  ticket code while writers are active.
- A ticket worker owns exactly one ticket and worktree, creates no agents, and
  returns the evidence required by
  [the worker contract](references/worker-contract.md).
- Standards and Spec reviewers independently inspect the same committed range.
  The original worker and registered reviewers close eligible repairs through
  the [ticket review loop](../../../docs/agents/ticket-review-loop.md), keeping
  their code ownership and independent findings.

Before every agent follow-up, confirm that the target still owns the same
ticket, role, and worktree.

If isolated worktrees or independent review contexts are unavailable, stop and
report that the requested frontier guarantee cannot be provided. Do not
simulate independence in one mutable checkout.

## Execution profile

Before dispatch, read the shared
[Subagent execution profile](../../../docs/agents/subagent-execution-profile.md)
for coordinator recommendations, explicit role settings, fresh contexts, and
trial evidence. Keep the ownership and review boundaries above.

## Process

1. Read every ticket and blocker. Pin each base, verify code-producing blocker
   commits are ancestors, reject missing references/cycles, and compute the
   open frontier.
2. Classify frontier tickets as parallel-safe, order-sensitive, or coupled.
   Add an edge for a clear shared first writer; stop for ticket reshaping when
   coupled tickets cannot remain independently green.
3. Present the wave, worktrees/branches, likely touch areas, checks, conflicts,
   and required operations. Reuse the shared standing authorization for its
   named local Git operations; obtain one decision only for missing mutation
   capabilities. Cleanup remains separate.
4. Create isolated writers only after authorization. Read the
   [worker contract](references/worker-contract.md) and give each writer a
   focused brief with repository paths rather than coordinator transcripts.
   On the first review-ready result, register the two independent reviewers,
   their report paths, and the ticket review loop's repair boundary.
5. Let the registered roles finish the ticket review loop. Receive its
   review-complete packet or an escalation; do not relay each routine finding
   or reconstruct receipts already supplied. Resolve escalations and inspect
   agreed high-risk seams before accepting the ticket for integration.
6. When integration is authorized, integrate reviewed ticket commits in
   topological order. A conflict stops integration for direction. Run focused
   cross-ticket checks after each wave and the required repository suite after
   the authorized frontier is integrated.
7. Keep one existing execution ledger/state as the result index, linking the
   original worker, review, and gate records. Update tracker records only when
   that separate capability was granted, then recompute the frontier from
   repository/tracker state. Apply final acceptance before reporting delivery.

## Completion

Stop at the authorized frontier or first unresolved blocker. Report every
ticket's base, branch, worktree, commit, checks, two-axis review disposition,
integration state, remaining frontier, and worktrees left in place. An
implemented but unintegrated or unverified ticket is not complete.

All implementation follows the shared
[test, review, and authorization standards](../../../docs/agents/delivery-standards.md).
