# Ticket 07 Standards review — Reactor context templates

Axis: Standards only

Fixed base: `f3ef17e028802d4072e943c167f9abea0aca5b3a`

Reviewed head: `77f93f0f468f0beec6e35b2c8f9ca9c385908c51`

Range: `f3ef17e028802d4072e943c167f9abea0aca5b3a..77f93f0f468f0beec6e35b2c8f9ca9c385908c51`

Range integrity: clean worktree; HEAD equals the reviewed head; the range is one commit whose direct parent and merge base are the fixed base; `git diff --check` passes. The range contains the reported ten files and no others.

## Result

Clean — 0 Standards findings. Worst severity: none.

## Review basis

Reviewed the complete ticket 07 issue, `spec.md`, `interface-draft.md`, repository `AGENTS.md`, ADR 0003, ADR 0018, ADR 0034, ADR 0037, the Product Surface status document, the repository review skill and delivery standards, the full ten-file diff, current Kernel Holder/Snapshot implementation, and worker verification/dependency evidence. The Spec-axis report was not read.

The implementation follows native Reactor subscription-context semantics: `withContext`/`propagate` install the value through `contextWrite`, `mapInContext` reads `ContextView` inside `deferContextual` once per subscription, and the immutable snapshot opens a thread-owned Holder scope separately around each actual synchronous mapper invocation. Missing state becomes an explicit absent snapshot, a wrong-typed managed value fails before the mapper, and neither path falls back to the signal thread Holder.

The restoration lifecycle is stack-bound to the mapper invocation, so normal return, mapper failure, retry, and cancellation notification cannot close a still-running callback from another thread. The bounded real-scheduler tests exercise delayed subscription after parent-Scope exit, foreign worker restoration, absent and invalid native state, concurrent Tenant/Platform/same-Tenant-different-identity subscriptions, retry, original exception propagation, native-map non-propagation, and cancellation while the synchronous mapper remains active.

The dependency boundary is appropriate: Reactor stays in the optional adapter, Kernel has no Reactor dependency, the public adapter is BOM-managed, and the standalone consumer has no Spring AI dependency. Documentation states the trusted-write boundary, fixed-context Publisher lifetime, lack of global hooks or arbitrary operator/inner-Publisher propagation, blocking-I/O scheduler responsibility, and the separate tenant-isolation responsibility for `cache`/`share` data.

## Evidence checked

- Worker scoped verify: PASS with 43 Kernel tests, 8 adapter tests, and 2 independent-consumer tests; Enforcer, dependency convergence, Spotless, PMD, JaCoCo, compilation, and packaging completed successfully.
- Resolved Reactor Core version: `3.8.7`.
- Dependency trees: Kernel contains no Reactor; independent Reactor consumer contains no Spring AI.
- No additional build was run because the immutable reviewed head already has current scoped verification logs and the source review found no concrete gap requiring a redundant run.
