---
name: code-review
description: Review a Moduvera diff from an explicit fixed point on independent Standards and Spec axes and report actionable findings without editing.
---

# Code Review

Reviewers inspect source read-only and own their separate review reports.
They do not fix code, run the quality gate, or create commits.

## Execution profile

Before dispatch, read the shared
[Subagent execution profile](../../../docs/agents/subagent-execution-profile.md).
Apply its reviewer settings and separate fresh contexts to both axes; keep
the user's active main-task model and effort.

## Process

1. Resolve the user-supplied comparison point and capture `git diff
   <fixed-point>...HEAD` plus `git log <fixed-point>..HEAD --oneline`. Stop on
   an invalid ref or empty range. For a three-dot comparison, resolve its
   merge base as the immutable review base. Run the shared
   [review preflight](../../../docs/agents/delivery-standards.md#review-and-gate-evidence)
   before dispatching either axis.
2. Resolve the originating ticket/spec from the user, commit references, or
   `.scratch/`. Read it in full. Read `AGENTS.md`, applicable ADRs, and other
   standards sources.
3. Run two independent review contexts against the same immutable range:
   - **Standards:** repository rules, architecture, correctness/security risks,
     test quality, and relevant design smells. Repository rules override
     generic heuristics; distinguish hard violations from judgment calls.
   - **Spec:** missing/partial requirements, incorrect behavior, scope creep,
     and inadequate acceptance evidence, quoting the requirement for each.
4. Require each finding to include severity, exact file/line evidence,
   rationale, and concise remediation direction. A clean axis says so directly.
   If independent contexts are unavailable, stop rather than claim a completed
   dual-axis review.
5. Present the two reports separately without reranking them into one list.
   In a registered frontier
   [ticket review loop](../../../docs/agents/ticket-review-loop.md), deliver each
   original report to its assigned evidence path and notify the original
   worker directly. Follow that loop for repair review and escalation;
   standalone review still stops at the report.

## Completion

Stop after reporting the fixed base/head, finding counts and worst finding per
axis, or the exact reason an axis could not run. Accepted findings return to
the original implementer; any fix creates a new diff that must be reviewed
before gate evidence is current. Each reviewer alone confirms closure of its
findings and issues its result for the new immutable pair.

Follow the shared [review evidence and authorization rules](../../../docs/agents/delivery-standards.md).
