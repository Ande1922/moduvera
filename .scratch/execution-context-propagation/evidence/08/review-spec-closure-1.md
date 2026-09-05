# Ticket 08 Spec review — closure 1

- Axis: Spec
- Reviewer role: independent leaf reviewer
- Requested profile: `gpt-5.6-sol` / `high`
- Fixed base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Fixed head: `0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7`
- Reviewed range: `d5db8666882eeb7f3cc09cf641674cf44df59428` plus closure commit `0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7`
- Result: original P1 closed; 0 actionable Spec findings

## Original finding disposition

**Closed — effective request/default reserved-key collisions are now rejected.**

The original report required the adapter to validate the effective native request rather than only caller-repeated maps. `RequestContext.applyTo(...)` now adds non-reserved configuration and a request-scoped `NativeExecutionContextAdvisor` at `framework/adapters/moduvera-spring-ai-context/src/main/java/io/github/ande1922/moduvera/ai/SpringAiExecutionContexts.java:147-157`. The Advisor has `Ordered.HIGHEST_PRECEDENCE` and validates the fully assembled `ChatClientRequest.context()` plus the effective `ToolCallingChatOptions.getToolContext()` before injecting the captured object at lines 160-218. The resolved Spring AI 2.0.1 chain sorts this Advisor ahead of downstream model/tool Advisors, so rejection occurs before model delegation.

The independent consumer covers the six effective collision surfaces requested by the finding: shared default Advisor context, direct request Advisor context, shared default ToolContext, direct request ToolContext, request `ToolCallingChatOptions`, and default `ToolCallingChatOptions`. Each case asserts an exception and zero scripted-model calls at `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/SpringAiContextConsumerTest.java:45-101`.

Matching reserved values and non-reserved configuration are retained across default, request, request-options, and adapter-supplied Advisor/ToolContext inputs at lines 103-163. Messages and temperature remain intact. The adapter rebuilds the effective concrete `ToolCallingChatOptions` through its public `mutate()` contract, which is defined to initialize the new builder from the current options, and only adds the reserved ToolContext entry; a request without the native ToolContext carrier fails before the model at lines 165-185. This preserves provider-specific implementations that honor the Spring AI options contract without introducing a generic replacement options object.

The pre-existing true stream evidence remains intact: capture occurs before subscription, model signals are delayed and emitted on a model scheduler, responses shift to worker threads holding a foreign context, concurrent Tenant/same-Tenant-different-identity/Platform responses restore the correct request context only during the synchronous mapper, and success/failure restore the worker Holder. The same shared `ChatClient` has an unbound follow-up with no request identity. Ticket 09 continues to own ToolCallback wrapping and the multi-round tool loop.

## Evidence checked

- Exact base/head resolution, two-commit log, complete fixed-range diff, closure diff, and clean worktree.
- Updated worker report, original independent Spec report, full ticket 08 acceptance criteria, relevant parent-spec ID07/T06/T08/T09 requirements, and ticket map ownership.
- Updated production adapter, README, unit tests, independent public consumer, scripted model, Maven/BOM changes, and ADR 0037 claim.
- Resolved Spring AI 2.0.1 primary source for request/default assembly, Advisor ordering/delegation, native request/response contexts, and `ToolCallingChatOptions.mutate()` behavior.
- Existing regression-first evidence: the collision test fails against the original implementation; final focused verify passes Kernel 37, adapter 4, and consumer 5 tests at the fixed head. Dependency evidence remains Spring AI 2.0.1 / Reactor 3.8.7 / Spring Framework 7.0.9 with Kernel and the non-AI consumer free of Spring AI dependencies.

No additional test repetition was needed for this closure review. No repository code, tracker, gate, branch, commit, agent, or integration state was changed.
