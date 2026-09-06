# Delivery standards

These invariants apply to every implementation path selected by the
[delivery workflow](delivery-workflow.md). Stage Skills link here rather than
copying the rules.

## Test obligation and implementation order

Every behavior change needs risk-proportionate evidence before Clean Code and
formal review. Reuse the highest existing observable seam; do not add a public
seam solely to make a test convenient.

- For an actual bug, first add a regression that fails for the reported
  behavior.
- For complex Domain rules, state transitions, idempotency, authorization, or
  Tenant isolation, test-first development is recommended.
- Other work may add tests before or after implementation, but the affected
  narrow tests must pass before Clean Code begins.
- Load the project `tdd` Skill only when the user explicitly invokes it or a
  nearer repository instruction requires TDD. Ordinary implementation still
  carries the test obligation without imposing TDD sequencing.

After narrow checks are green, perform one Clean Code pass over the ticket
diff: simplify names and control flow, remove duplication and speculative
abstraction, preserve behavior, then rerun every affected narrow check. The
reviewed diff is the post-cleanup diff.

## Review and gate evidence

Before dispatching review or starting final Scenario verification, run
`python3 tools/quality/review_preflight.py --base <resolved-base> --head <resolved-head>`.
Reuse a passing result for the same immutable pair. This read-only preflight
checks whitespace, changed Markdown links, and Skill structure using the gate's
existing checks and returns resolved refs, diff SHA-256, checkout identity,
timestamp, and its exit code. It does not replace either review axis or the
quality gate. Default mode checks the committed range even in a dirty checkout.
On failure, report the failed check and return to the authorized implementer.

The `git-diff-tree-raw-v1` fingerprint hashes NUL-delimited raw changes with
full object IDs and modes, renames disabled and canonical path order. It is
independent of patch display settings; it is not the SHA-256 of a rendered patch.

For a ticket review handoff, the worker adds `--repo <worktree>
--require-current-clean --expected-branch <registered-branch>` and stores stdout
in the external evidence directory with the command's actual exit code. This
mode also verifies the current head, clean checkout, and registered branch.
Run it once per frozen handoff for both reviewers; recheck current state after
any edit or role handoff rather than treating an old checkout receipt as live.
Keep receipts outside the worktree so recording one does not dirty its input.

Keep each verification command's exit code with its output. A later successful
command or truncated output cannot establish that an earlier check passed.
For long-running builds and Scenarios, use 30–60 second output waits after an
initial status check; shorten them only when prompt interaction is needed.

Pin Standards and Spec review to the same resolved base and head. Record that
each axis completed, keep their findings independent, and resolve every
accepted finding before the quality gate. The
repository gate owns change classification and runs from a clean checkout:
call `tools/quality/quality-gate.sh`; do not reproduce its path rules in a
Skill or hook.

A gate result applies only to its recorded base, head, ref, profile, and clean
checkout state. Final PASS additionally requires both completed review axes to
name the same base/head. Any later source, test, configuration, documentation,
or mode change invalidates that evidence and requires the affected review plus
gate to run again. A nonzero gate, missing or mismatched review evidence,
unresolved accepted finding, dirty checkout, or unrun applicable Scenario gate
is not PASS.

## Authorization boundary

Read-only discovery, planning, review, and local non-mutating checks need no
additional authority. Treat each of these as a separate mutation capability:

- edit repository files;
- create commits;
- create or remove branches and worktrees;
- merge, rebase, or otherwise integrate commits;
- push, publish, deploy, or change external systems;
- update or close tracker records.

Use only capabilities explicitly granted for the current run, including the
applicable standing authorization in
[shared Codex permissions](codex-permissions.md#routine-local-authorization).
Check that scope before requesting confirmation again. Stop
at the stage boundary and report the required capability when authority is
missing. Never infer push, tracker, integration, cleanup, or deployment
authority from permission to implement or run tests.
