---
name: quality-gate
description: Run and report the repository-owned quality gate against an explicit comparison point after review findings are resolved.
---

# Quality Gate

Read [the gate contract](../../../docs/quality-gate.md) and shared
[delivery standards](../../../docs/agents/delivery-standards.md). The script is
the only owner of classification and checks; this Skill never reproduces its
path rules or weakens its commands.

## Process

1. Resolve and record the reviewed base and intended head. Require the checkout
   to be clean and exact at that head.
2. Use `auto` for repository classification or `normal` to upgrade to the full
   gate. There is no caller-selectable docs-only mode.
3. Run `tools/quality/quality-gate.sh <auto|normal> --base <revision>
   --head <revision> --ref <description>` and preserve its exit status.
4. Report the mode, profile, base, head, ref, evidence path, and every failed
   required step without exposing the private full log.

## Completion

Stop when the gate exits. A nonzero exit, stale/missing evidence, dirty state,
or source change after the run is FAIL, never PASS. Running the gate does not
authorize committing, pushing, tracker updates, or deployment.
