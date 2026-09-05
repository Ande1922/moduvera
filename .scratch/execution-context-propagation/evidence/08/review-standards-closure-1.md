# Ticket 08 Standards review — closure 1

## Fixed point and result

- Axis: Standards — correctness/security, dependency boundaries, request/response lifecycle, API boundary, and test quality; completed independently and read-only.
- Worktree: `/private/tmp/execution-context-frontier-20260905/08`
- Branch: `codex/execution-context-20260905-08`
- Base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Head: `0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7`
- Range: two commits, `d5db866 feat: propagate execution context through spring ai` and `0e5c6db fix: reject conflicting spring ai request context`; `git merge-base` equals the supplied base.
- Checkout state: clean before and after review; full-range and closure-diff `git diff --check` pass.
- Disposition: the accepted P1 is resolved. Full fixed-range conclusion: clean, 0 remaining actionable Standards findings. Worst remaining severity: none.

## Accepted P1 disposition

**Resolved — effective reserved-key conflicts now fail before model delegation.** `RequestContext.applyTo(...)` adds only non-reserved caller configuration and one immutable request-scoped Advisor (`framework/adapters/moduvera-spring-ai-context/src/main/java/io/github/ande1922/moduvera/ai/SpringAiExecutionContexts.java:143-157`). The Advisor receives the assembled `ChatClientRequest`, copies and validates the effective Advisor context, reads and validates the concrete `ToolCallingChatOptions` ToolContext, and mutates only after both checks pass (`SpringAiExecutionContexts.java:190-208`). Different values are compared by identity to the one strict capture and rejected at lines 211-217; matching values remain valid.

This closes the original opaque fluent-state bypass. In resolved Spring AI 2.0.1, `DefaultChatClientRequestSpec.stream()` first converts the complete request and builds the Advisor chain, including defaults and direct request configuration, before any Advisor runs. The chain sorts by Spring order and invokes `Ordered.HIGHEST_PRECEDENCE` first. Thus the new Advisor sees effective default/direct Advisor parameters and merged default/direct/options ToolContext before delegating to later Advisors or the model. It no longer relies on callers repeating hidden request state into `captureRequest(...)`.

The independent public consumer covers all six material collision inputs at `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/SpringAiContextConsumerTest.java:45-101`: default and direct Advisor parameters, default and direct ToolContext, and default and direct `ToolCallingChatOptions`. Each scenario asserts the exception and zero `ScriptedChatModel` calls. The regression-first log records the original implementation reaching the model instead of throwing; the final log runs all six cases green.

## Regression assessment

The capture remains singular and trusted. `captureRequest(...)` reads `ExecutionContextHolder.require()` once at `SpringAiExecutionContexts.java:48-54`; neither `applyTo(...)` nor the new Advisor consults the Holder or another identity source. The Advisor stores one final captured reference and is otherwise stateless, so delayed subscription and cross-thread execution retain the request's original identity rather than the subscriber thread's state.

Native request configuration is retained through supported framework contracts. The Advisor starts from the assembled request context, adds the reserved value to its copy, uses the concrete `ToolCallingChatOptions.mutate()` contract to preserve the most concrete builder and provider-specific settings, adds the reserved ToolContext entry, and rebuilds the existing Prompt through `Prompt.mutate()` (`SpringAiExecutionContexts.java:190-208`). It does not convert options to the default implementation. The positive consumer at `SpringAiContextConsumerTest.java:103-163` combines matching default/direct reserved values with default, direct, adapter, and options ToolContext entries, Advisor entries, system/user messages, and temperature, then verifies all remain at the scripted model/response. The separate negative case at lines 165-185 makes the API boundary explicit: a request without native `ToolCallingChatOptions` fails before model delegation rather than silently losing the second carrier.

The highest-precedence placement makes the captured native carriers available to the downstream Spring AI chain, including the later ToolCallingAdvisor, without adding Holder scope around arbitrary Advisor execution. Tool callback wrapping and multi-round tool-loop validation remain assigned to ticket 09. The existing response mapper remains stateless and restores only around its synchronous delegate; its delayed, thread-shifted, concurrent Tenant/Platform and failure-path evidence is unchanged.

The closure does not change Maven topology or dependencies. Spring AI and Reactor remain confined to the optional Adapter/consumer path; Kernel and non-AI consumers remain unaffected. Documentation now accurately states final-request validation, concrete ToolCallingChatOptions support, provider-option preservation through `mutate()`, and the ticket 09 boundary.

## Evidence reviewed

- `worker-report.md`: refreshed base/head, implementation boundary, exact commands, versions, and ticket 09 handoff.
- `collision-regression-red.log`: the new collision regression fails against the original behavior because no exception was raised before the model.
- `affected-verify-after-p1-final.log`: Kernel 37, Adapter 4, and independent consumer 5 tests pass; Enforcer, compilation, Spotless, PMD, JaCoCo, and reactor build finish with `BUILD SUCCESS`.
- Resolved Spring AI 2.0.1 source JARs: request assembly occurs before Advisor execution; Advisor ordering is deterministic; `ChatOptions.mutate()` requires the most concrete builder, `ToolCallingChatOptions.mutate()` preserves its initialized values, and `Prompt.mutate()` retains messages/options before the targeted options replacement.
- Full source and diff from the supplied base through the new head, plus closure diff `d5db8666882eeb7f3cc09cf641674cf44df59428..0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7`.

No build was repeated because the post-fix focused verify is current for the immutable head and source/JAR inspection resolved the API and ordering questions. This report is the Standards axis only; it does not perform the quality gate, tracker changes, integration, or source edits.
