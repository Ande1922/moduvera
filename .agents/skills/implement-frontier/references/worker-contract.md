# Ticket worker contract

Use this contract only after the coordinator has authorized and prepared an
isolated worktree.

## Required brief

The coordinator supplies the canonical ticket and full content, exact
worktree/branch/base, acceptance criteria and test seams, likely touch areas
and collision risks, repository/domain references, scoped validation commands,
and explicit edit/commit authority. Stop before editing when any item is absent
or contradicts the worktree.

## Execution

1. Confirm worktree, branch, clean start, and exact base.
2. Restate the single-ticket outcome, scope, exclusions, and public seams.
3. Inspect only the relevant implementation and prior art.
4. Follow [repository delivery standards](../../../../docs/agents/delivery-standards.md):
   add risk-proportionate tests, use regression-first for actual bugs, and load
   the project [`tdd`](../../tdd/SKILL.md) Skill only when explicitly invoked
   or a nearer repository rule requires it.
5. Run narrow checks throughout. After they pass, perform Clean Code and rerun
   affected checks, then run ticket/package validation.
6. Review the diff for acceptance, standards, scope expansion, generated
   artifacts, secrets, and unrelated changes.
7. Create an ordinary ticket commit when authorized. Do not amend or rewrite
   history.
8. Return the evidence report below and stop. Do not merge, rebase, push,
   update trackers, remove worktrees, begin another ticket, or create agents.

## Scope invariants

- One worker owns one ticket and one worktree.
- Preserve unrelated user changes and keep the branch independently verifiable.
- Treat migrations, lockfiles, generated indexes, central registries, and broad
  snapshots as collision surfaces.
- Use no shared stash or uncommitted state outside the worktree.
- Report cross-ticket dependencies to the coordinator rather than absorbing
  them.

## Evidence format

```text
Ticket: <canonical reference — title>
Worktree: <absolute path>
Branch: <branch>
Base: <sha>
Result: <commit sha | blocked>

Acceptance evidence:
- <criterion>: <observable evidence>

Changed files:
- <path>: <reason>

Verification:
- <exact command>: <pass/fail and concise result>

Risks or blockers:
- <none or concrete item>
```
