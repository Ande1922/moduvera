---
name: to-spec
description: Synthesize an agreed Moduvera requirement or conversation into one repository tracker specification with explicit behavior, test seams, decisions, and exclusions.
---

# To Spec

Synthesize existing decisions; this stage is not a second interview. If a
material product or architecture decision is absent, stop and route that
question to [`grill-with-docs`](../grill-with-docs/SKILL.md).

## Process

1. Use the [task context rules](../../../docs/agents/domain.md#task-context) and local
   [tracker conventions](../../../docs/agents/issue-tracker.md).
2. Inspect enough current code and tests to identify the highest stable
   observable test seams. Confirm any new public seam with the user.
3. Classify the scope using [task complexity](../../../docs/agents/task-complexity.md).
   Write one spec under `.scratch/<feature-slug>/spec.md` with `Type: spec`, an
   allowed status, `Complexity:`, and `Complexity basis:`. Record the problem,
   outcome, decisions, numbered acceptance criteria and test seams, and
   exclusions, adding the detail required by its level. User Stories are
   optional when roles and goals add distinct information; they have no quota.
   Record contracts rather than volatile code snippets or file-by-file instructions.
4. Check the complexity rating against its cited scope and verification needs,
   then check terminology, ADR compatibility, coverage, and local links.

## Completion

Stop after returning the spec path, complexity and basis, chosen test seams, unresolved decisions,
validation performed, and a recommended implementation route with one brief
reason under [route by task shape](../../../docs/agents/delivery-workflow.md#route-by-task-shape).
Publishing or editing requires repository-write authority. Ticketing or
implementation requires spec approval and authority for that stage; reuse
approvals already granted for the current delivery.

Follow the shared [test and authorization standards](../../../docs/agents/delivery-standards.md).
