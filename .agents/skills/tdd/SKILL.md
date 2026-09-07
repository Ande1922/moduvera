---
name: tdd
description: Run a repository-scoped red-green loop at agreed observable seams when the user explicitly invokes TDD or a nearer repository rule requires it.
---

# Test-Driven Development

This supporting Skill is explicit or repository-required only. Use the
[task context rules](../../../docs/agents/domain.md#task-context) to resolve
the applicable vocabulary, ADRs, and repository testing rules.

Before writing a test, name the observable seam and confirm any new public seam
with the user. Prefer integration-style behavior through a real public
interface; mock only external system boundaries, time, randomness, or a file
system boundary where a real test is disproportionate. Do not mock repository
internals or assert private call order.

For each vertical slice:

1. Add one behavior test with an independent expected result.
2. Run it and confirm it fails for the intended missing behavior.
3. Add only enough implementation to make it pass.
4. Run the narrow test and relevant neighboring tests.
5. Repeat for the next behavior.

## Completion

Stop when the agreed behavior is green. The post-green cleanup check belongs
to the enclosing [`implement`](../implement/SKILL.md) workflow, with affected
test reruns after any cleanup edits. Report any test that could not be made
meaningfully red; do not count a tautological or implementation-coupled test as evidence.

The enclosing workflow retains the shared
[authorization boundary](../../../docs/agents/delivery-standards.md#authorization-boundary).
