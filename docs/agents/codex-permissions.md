# Shared Codex permissions

This repository supplies portable command rules in
[`.codex/rules/routine-git.rules`](../../.codex/rules/routine-git.rules) and
project defaults in [`.codex/config.toml`](../../.codex/config.toml).
There are no personal paths, cache directories, credentials, or host-specific
settings. Keep rules in the repository; do not copy them into a developer's
global allowlist.

## Activation

Use a Codex client that supports project configuration, execpolicy rules, and
`approvals_reviewer = "auto_review"`. Trust this repository's project layer,
restart Codex or reopen the task after pulling configuration changes, and
confirm the task uses **Approve for me**. The project defaults select
`on-request` with `auto_review`; they do not change the developer's sandbox.

Each worktree needs the tracked `.codex/` files and a trusted project layer.
A worktree created from an older revision without these files does not carry
the rules. Until these changes are committed, new worktrees do not inherit
the uncommitted files. Do not automatically modify older branches to add them.

Managed restrictions and explicit developer overrides take precedence.
Global rules also participate: this file is an additional allowlist, not a
replacement for every developer's existing policy. Do not claim that a
successful parser check proves a running task loaded the rules.

## Routine local authorization

For a task that requests implementation or fixes, this repository grants
standing authorization for the following local Git operations when needed
for that task. Read-only requests remain read-only, and explicit user limits
such as “do not commit” or “no worktrees” take precedence. Authorization is
permission to use these operations, not an instruction to create a commit
or worktree after every edit.

Run one Git command per tool call with its working directory set to the
intended checkout. Use these portable forms rather than `git -C`, machine
paths in rule patterns, shell wrappers, or an interpreter that runs Git:

| Purpose | Command form |
| --- | --- |
| Stage task-owned paths | `git add -- <paths>` |
| Ordinary commit | `git commit -m <message>` or `git commit -F <message-file>` |
| Existing-branch worktree | `git worktree add -- <destination> <branch>` |
| New-branch worktree | `git worktree add -b <new-branch> -- <destination> <start-point>` |
| Detached worktree | `git worktree add --detach -- <destination> <revision>` |
| Switch existing branch | `git switch -- <branch>` |
| Create and switch branch | `git switch -c <new-branch> -- <start-point>` |
| Compatible new-branch checkout | `git checkout -b <new-branch> <start-point>` |
| Fast-forward integration | `git merge --ff-only -- <reviewed-branch>` |
| Fetch configured remote | `git fetch -- <remote-name>` |

Inspect working-tree and staged changes before mutations. Stage only owned
paths; inspect the full staged diff before committing so another developer's
already-staged work is preserved. Preserve hooks and normal Git checks. Use
explicit task destinations and start points. Follow the delivery workflow's
review and verification requirements before integrating.

History rewrites, force/discard options, hook bypass, branch/worktree deletion,
push, publish, deployment, and external writes are outside this standing
authorization. Prefix rules do not inspect trailing options: appending such
an option to a matching prefix is still outside the authorized operation.
The `match` and `not_match` entries are loading-time examples, not additional
argument filters. Command rules also do not constrain the working directory
or inspect commit hooks; use them only in the trusted task checkout.

## Conflicts and requests outside the list

Before changing branches or starting integration, inspect `git status` and
`git diff --name-only --diff-filter=U`. If unresolved paths or a merge,
rebase, or cherry-pick already in progress are present, stop and report the
operation, branches, and affected paths to the user. If an attempted operation
introduces conflicts, preserve that state and ask for a decision. Do not
select ours/theirs, discard changes, or continue the operation automatically.

A fast-forward failure by itself is not proof of a content conflict. Inspect
the divergence read-only; a different integration strategy requires the
task's integration authority and the ordinary approval route.

Commands not matched here keep the active sandbox and approval policy. Under
Approve for me, an eligible boundary crossing goes to automatic review.
If review denies the action, use a materially safer authorized alternative;
otherwise present its concrete denial to the user. User confirmation cannot
override managed prohibitions. Code conflicts require a user decision even
when an underlying command would otherwise be allowed by a rule.

This first shared list covers Git. Builds and tests continue inside the
sandbox where possible; missing dependency, network, Docker, or cache access
uses the same review route. No global Maven, Docker, shell, or Python escape
is granted by these files.

## Verification and token comparison

Run the portable matching tests from the checkout root:

```bash
python3 -m unittest discover -s tools/codex-permissions/test -v
```

The tests require a `codex` executable on PATH, use its actual
`execpolicy check`, and never execute the proposed Git commands. They verify
allowed and unmatched forms and repeat checks from a relocated checkout.
They test these repository rules alone. To inspect interaction with another
rules file, pass both files as repeated `--rules` arguments to
`codex execpolicy check`; its strictest matching decision wins.

After activation, use a bounded development task to compare automatic review
requests, manual interruptions, cached/uncached input, output tokens, and
verification results. Separate conflict decisions from permission prompts.
Record the rules revision, client, and effective approval mode with the
sample. Fewer automatic review requests should reduce their processing;
neither matcher tests nor log token counts establish a billed saving.

## References

- [Codex rules](https://learn.chatgpt.com/docs/agent-configuration/rules)
- [Auto-review](https://learn.chatgpt.com/docs/sandboxing/auto-review)
- [Configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)
