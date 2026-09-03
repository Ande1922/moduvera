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

Use only capabilities the user explicitly granted for the current run. Stop
at the stage boundary and report the required capability when authority is
missing. Never infer push, tracker, integration, cleanup, or deployment
authority from permission to implement or run tests.
