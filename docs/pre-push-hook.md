# Local pre-push quality gate

The repository includes an opt-in pre-push hook. It is never installed by
Maven, tests, or ordinary development commands. Enable it explicitly from this
checkout after the hook files are committed:

```bash
python3 tools/git-hooks/hooks.py install
```

The installer writes only this repository's local `core.hooksPath`, setting it
to `.githooks`. Repeating the command is safe when that one local value is
already present. The installer makes no change when a local, global, system, or
other effective hooks path would be displaced, and it does not enable Git's
worktree-specific configuration. Empty or multiple local values are conflicts,
not an absent setting. Before installation, the installer also verifies that
both the hook and its Python implementation are unchanged executable files
whose worktree, index, and `HEAD` identities match.

Repository-local configuration is shared by linked worktrees. Enabling this
hook from one linked worktree therefore selects `.githooks` for its siblings as
well; a sibling whose checkout does not contain the integrated hook could skip
the intended gate. Install only after the hook commit has been integrated into
every push-capable linked worktree. An active worktree-scoped override is never
replaced. A foreign effective override blocks installation, while any
worktree-scoped hooks-path entry blocks uninstall without changing either
configuration because removing the shared local value could not prove that the
hook was effectively disabled.

Disable the hook with:

```bash
python3 tools/git-hooks/hooks.py uninstall
```

Uninstall removes only a single local value that is still exactly
`.githooks`. A foreign or multiple local value is left untouched and reported
as a conflict. Any global configuration is always left untouched. Uninstall is
an escape hatch: once the sole local value is confirmed as owned, missing,
modified, staged, or non-executable hook assets do not prevent its removal.

## Push behavior

Git supplies one LF-terminated four-field record for every ref in a push. The
hook requires exactly three ASCII-space delimiters per record, validates the
complete input against the repository's object format, peels each non-deletion
object to a commit, and requires every peeled local object to be the checkout's
exact `HEAD`. It then selects a single conservative comparison base:

- an existing remote ref uses the peeled old commit advertised by the remote;
- a new remote ref uses the unique merge-base of `HEAD` and the remote's
  current symbolic default `HEAD`, queried with `git ls-remote --symref`;
- multiple ref bases are reduced to one unique common ancestor, so one
  invocation covers the union of their changes.

The hook never fetches. Missing local objects, divergent history, ambiguous or
missing merge-bases, malformed input, and deletion-only pushes fail closed.
Deletion records in a mixed push are structurally validated but their old
objects need not exist locally because deletions do not contribute comparison
ranges. One local source may target several distinct remote refs; duplicate
remote targets remain invalid.

Before formal evidence starts, the hook rejects staged or unstaged changes,
non-ignored untracked files, and assume-unchanged or skip-worktree index flags.
It does not print affected paths, file contents, the remote URL, or ref names.
The evidence ref is a deterministic bounded label containing only the ref count
and SHA-256 digest of the sorted remote ref names. Once the inputs are resolved,
the hook replaces itself with exactly one invocation of:

```text
tools/quality/quality-gate.sh auto --base <commit> --head <HEAD> --ref <opaque push label>
```

The repository quality gate remains the only profile classifier and evidence
owner. Its exit status and terminating signal propagate back to Git.

Run the isolated real-Git fixture suite with:

```bash
tools/git-hooks/test/run-tests.sh
```
