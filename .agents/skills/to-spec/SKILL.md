---
name: to-spec
description: Synthesize an agreed Moduvera requirement or conversation into one repository tracker specification with explicit behavior, test seams, decisions, and exclusions.
---

# To Spec

Synthesize existing decisions; this stage is not a second interview. If a
material product or architecture decision is absent, stop and route that
question to [`grill-with-docs`](../grill-with-docs/SKILL.md).

## Process

1. Read `AGENTS.md`, `CONTEXT.md`, applicable ADRs, and the local
   [tracker conventions](../../../docs/agents/issue-tracker.md).
2. Inspect enough current code and tests to identify the highest stable
   observable test seams. Confirm any new public seam with the user.
3. Write one spec under `.scratch/<feature-slug>/spec.md` with `Type: spec` and
   an allowed status. Include Problem Statement, Solution, extensive numbered
   User Stories, Implementation Decisions, Testing Decisions, Out of Scope,
   and Further Notes. Record contracts rather than volatile code snippets or
   file-by-file implementation instructions.
4. Check terminology, ADR compatibility, scope, and local links.

## Completion

Stop after returning the spec path, chosen test seams, unresolved decisions,
and validation performed. Publishing or editing requires repository-write
authority. Do not split tickets until the user approves the spec and requests
that stage.

Follow the shared [test and authorization standards](../../../docs/agents/delivery-standards.md).
