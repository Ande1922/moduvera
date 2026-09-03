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
the gate before any check executes. Assume-unchanged and skip-worktree index
flags are also forbidden. Immediately before execution, each repository-owned
executable is checked for committed `100755` mode and content identity at the
recorded head. The gate then materializes a private, detached checkout of that
exact commit inside the evidence run and executes every check, extension, and
Maven phase there. Concurrent or transient changes in the invoking checkout
therefore cannot change official inputs. The private checkout invariant is
checked before and after every executable phase and once more before recording
PASS; a phase that changes its snapshot inputs fails the run. The snapshot is
removed before evidence finalization.

Both profiles run the gate self-tests, `git diff --check`, changed-Markdown
local-link checks, project Skill structure checks, and a high-confidence scan
of added content for credentials. Normal additionally runs exactly
`./mvnw -B -ntp clean verify`; it neither formats sources nor skips tests.
Local-link checks include the destination of detected Markdown renames and
copies, even though those change kinds conservatively select Normal.

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
Permissions are applied through no-follow file descriptors after confinement
checks, rather than through path-following chmod operations.
`.quality-gate/latest` is a private regular file containing the run ID of the
newest attempt, including an incomplete or failed base-resolution attempt. A
private monotonic attempt sequence, allocated under the evidence lock, defines
latest and retention ordering even if the system clock moves backward. Existing
evidence without sequence metadata is migrated once in deterministic run-ID
order. An older attempt completing later cannot move `latest` backward. Only
the newest 20 completed runs are kept. Required retention pruning succeeds
before the current summary and `completed` marker are published, so a pruning
failure cannot leave an authoritative PASS run. A private, no-follow, owner-validated
cross-process lock serializes existing-run validation, run creation, retention
reservation, summary and completion publication, monotonic sequence allocation,
and conditional `latest` update. Concurrent creation therefore cannot race
pruning into recreating an incomplete orphan, and concurrent completions share
the same 20-run limit. Each invocation retains a private active marker containing
its attempt sequence plus the owner PID and operating-system process birth
identity. A matching live owner is never reclaimed, and PID reuse is detected
by the birth mismatch. Before creation and pruning, a dead or birth-mismatched
completed owner is unpinned, while incomplete stale runs—including a crash
before active-marker publication—are removed under the same lock. Final
release drains pending watched signals before unpinning, enforces retention,
then drains again under the lock before restoring caller handlers. Thus a signal
during release/pruning becomes authoritative INTERRUPTED evidence rather than a
terminal PASS, while crashed invocations cannot grow retention without bound.
The terminal prints only the bounded redacted summary; inspect `full.log`
locally when more detail is required. The complete log is streamed through a
stateful redactor before only the final redacted lines are retained. Credential
redaction keeps bounded single- and double-quote state across physical lines,
recognizes escaped quotes, suppresses oversized standalone-token continuations
across input chunks, and removes private-key blocks in full. The scanner
parses complete changed-head files and reports an assignment only when its span
overlaps an added line, so a changed multiline value cannot hide behind an
unchanged key. Exact environment placeholders remain allowed. Identifier and
code-expression exemptions apply only to Java source; config-like files remain
strict, while terminal redaction stays conservative for every file type. YAML
credential block scalars (`|` and `>`, including chomping and indentation
indicators and compact sequence mappings such as `- password: |`) are scanned
and redacted through their indentation-defined dedent; oversized open blocks
remain suppressed under a bounded state limit.
Gate phases are trusted, committed repository code executed from the pinned
private checkout; process lifecycle handling is not an adversarial code sandbox.
SIGINT/SIGTERM is masked through child launch and process group capture. Cleanup
tracks the ordinary process group through descendant exit, escalates resistant
members to SIGKILL, and completes interrupted evidence only after the direct
child has been reaped. Post-wait group detection and draining remain inside the
same interruption-safe lifecycle. Birth-identity and inherited
lifecycle-descriptor tracking adds best-effort cleanup for known detached
descendants, but does not claim containment against hostile code that
deliberately double-forks, creates a new session, and closes inherited
descriptors. The first interrupt remains recorded from process entry through
snapshot cleanup, evidence finalization, and retention pruning. Watched signals
are blocked before gate handlers are installed; inherited pending signals are
consumed into gate state before the caller's intended mask is restored. The
execution-to-finalization transition blocks watched signals before switching
to non-raising cleanup, so a signal in that boundary is latched rather than
escaping without completed evidence. Watched signals pending in the final
blocked window are consumed, then the gate handlers remain installed while
those signals are unblocked. After the authoritative interrupted summary is
refreshed, both signals are blocked again before either caller handler is
restored. A signal pending during that per-handler restore window is consumed
and recorded before the caller's mask is atomically restored; later signals
follow the caller's original disposition.

Run the deterministic policy and fixture suite directly with:

```bash
tools/quality/test/run-tests.sh
```
