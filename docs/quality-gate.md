# Repository quality gate

`tools/quality/quality-gate.sh` is the repository-owned quality entry point. A
manual run must pin its comparison point explicitly:

```bash
tools/quality/quality-gate.sh auto --base <revision>
tools/quality/quality-gate.sh normal --base <revision>
```

Only `auto` and `normal` are public modes. `auto` selects the internal
docs-only profile only when every changed path is a newly added or modified,
non-executable regular `.md` file. Empty, deleted, renamed, symbolic, unknown,
or mixed changes select Normal. `normal` is an explicit upgrade and
cannot be used to skip work. An absent or unresolvable base fails closed and
creates a new failed evidence run.

Official evidence runs only from a clean checkout whose `HEAD` exactly matches
the resolved `--head`. Staged, unstaged, or non-ignored untracked paths stop
the gate before any check executes. This pins the scripts, Maven wrapper, and
inputs used by the recorded commit. The same invariant is checked immediately
before and after every executable phase and once more before recording PASS;
an extension, build, or concurrent process that changes inputs fails the run.

Both profiles run the gate self-tests, `git diff --check`, changed-Markdown
local-link checks, project Skill structure checks, and a high-confidence scan
of added content for credentials. Normal additionally runs exactly
`./mvnw -B -ntp clean verify`; it neither formats sources nor skips tests.

Later repository checks plug into executable files under these stable seams:

- `tools/quality/checks.d/common/` runs in both profiles before Maven. The
  tracker consistency checker belongs here.
- `tools/quality/checks.d/normal/` runs after a successful clean Reactor build.
  Changed-code coverage and CRAP scoring belong here so they can consume only
  reports produced by the current run.

Only direct extension files committed at the resolved head as regular
executable (`100755`) blobs are loaded. Untracked, symbolic, out-of-tree, or
non-executable extension candidates fail closed rather than execute.
Extensions receive `QUALITY_GATE_BASE`, `QUALITY_GATE_HEAD`,
`QUALITY_GATE_REF`, `QUALITY_GATE_PROFILE`, and `QUALITY_GATE_RUN_DIR`. They
must be deterministic and return non-zero on failure. The orchestrator runs
them in bytewise filename order and preserves their complete output.

Evidence is written beneath the Git-ignored `.quality-gate/runs/<run-id>/`.
Directories are mode `0700`; the full log and bounded, redacted summary are
mode `0600`. Symlinked evidence components are rejected before use.
`.quality-gate/latest` is a private regular file containing the run ID of the
newest attempt, including a failed base-resolution attempt. Only the newest 20
completed runs are kept.
The terminal prints only the bounded redacted summary; inspect `full.log`
locally when more detail is required. Credential redaction recognizes quoted
JSON and shell-style assignments, and private-key blocks are removed in full.
SIGINT/SIGTERM cleanup tracks the phase process group through descendant exit,
escalates resistant members to SIGKILL, and completes interrupted evidence
only after the direct child has been reaped.

Run the deterministic policy and fixture suite directly with:

```bash
tools/quality/test/run-tests.sh
```
