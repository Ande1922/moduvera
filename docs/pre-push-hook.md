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
worktree-specific configuration.

Disable the hook with:

```bash
python3 tools/git-hooks/hooks.py uninstall
```

Uninstall removes only a single local value that is still exactly
`.githooks`. A foreign or multiple local value is left untouched and reported
as a conflict. Any global configuration is always left untouched.

## Push behavior

Git supplies one four-field record for every ref in a push. The hook validates
the complete input against the repository's object format and requires every
non-deletion object to be the checkout's exact `HEAD` commit. It then selects a
single conservative comparison base:

- an existing remote ref uses the old commit advertised by the remote;
- a new remote ref uses the unique merge-base of `HEAD` and the remote's
  current symbolic default `HEAD`, queried with `git ls-remote --symref`;
- multiple ref bases are reduced to one unique common ancestor, so one
  invocation covers the union of their changes.

The hook never fetches. Missing local objects, divergent history, ambiguous or
missing merge-bases, malformed input, and deletion-only pushes fail closed.
Deletion records in a mixed push are validated but do not weaken the comparison
base.

Before formal evidence starts, the hook rejects staged or unstaged changes,
non-ignored untracked files, and assume-unchanged or skip-worktree index flags.
It does not print affected paths, file contents, or the remote URL. Once the
inputs are resolved, it replaces itself with exactly one invocation of:

```text
tools/quality/quality-gate.sh auto --base <commit> --head <HEAD> --ref <push refs>
```

The repository quality gate remains the only profile classifier and evidence
owner. Its exit status and terminating signal propagate back to Git.

Run the isolated real-Git fixture suite with:

```bash
tools/git-hooks/test/run-tests.sh
```
