# Ticket 09 worker report

- Branch: `codex/execution-context-20260905-09`
- Exact implementation base: `f3ef17e028802d4072e943c167f9abea0aca5b3a`
- Ticket 08 integrated prerequisite: `5e80a8dcbdc44262098d2cef3771f1eeb813fa86` (confirmed ancestor)
- Initial delivery head: `bbb334f1226922889c1cd915f93d9aebe2d5c81e` (ordinary coordinator commit of the original five-file worker patch)
- Worktree HEAD at review-fix staged handoff: `bbb334f1226922889c1cd915f93d9aebe2d5c81e`
- Review-fix commit head: `c8f9669f230b0b446378eba3b9667f47d2eab093` (coordinator ordinary commit of the exact worker-staged patch)
- Review-fix staged patch SHA-256: `8c843f3332ae7beeaf78fe59e72b26fff80dde26b6f55babd0090bee9ec5342a`
- Index state: exactly the ticket 09 consumer test and ADR 0037 ticket 09 section are staged; no unstaged repository change

## Delivered behavior and acceptance mapping

1. `SpringAiExecutionContexts.toolCallback(...)` returns a stateless wrapper that captures only its delegate. Every context-aware invocation reads the current native `ToolContext`; the context-free entry point, null ToolContext, missing reserved key, and wrong value type fail before delegate invocation. Unit evidence runs invalid entries while another Holder value exists, proving there is no Holder fallback.
2. The wrapper opens `ExecutionContextHolder.Scope` only around the actual synchronous `delegate.call(input, toolContext)`. It passes the exact input and ToolContext, returns the exact result, propagates the same runtime exception object, and restores the prior Holder after success and failure.
3. `getToolDefinition()` and `getToolMetadata()` return the delegate's exact objects, preserving tool name, description, input schema, metadata, and Spring AI lookup behavior.
4. A real `ChatClient.stream().chatClientResponse()` chain with Spring AI 2.0.1 `ToolCallingAdvisor` drives four requests through two tool rounds and a third final-model round. The one shared production wrapper observes two Tenant A requests with distinct Actor/Initiator/Correlation, one Tenant B request, and one Platform request, reading the proper complete context on every bounded-elastic tool invocation.
5. A test-only barrier runs inside the production wrapper's delegate. All four first-round delegate calls must enter while their request Scopes are open before the main test releases any of them; entry and release each have a 10-second bound, and release is in `finally`. This converts cross-request/cross-tenant concurrency from scheduler likelihood into controlled overlap while leaving the second tool round unblocked.
6. The scripted model records 12 real model calls and the business delegate records 8 tool calls for the successful concurrent scenario. The test proves model threads, Spring AI bounded-elastic tool threads, and final response workers are distinct execution boundaries. Final responses pass through ticket 08 `responseMapper`; its synchronous callback observes Scope/Actor/Initiator/Correlation and the pre-existing worker Holder is restored immediately afterward.
7. The real framework tool loop also covers missing and wrong native contexts with zero business delegate calls, plus a delegate exception that remains the exact same object. A test-only outer ToolCallback records the exact Holder immediately before and after the production wrapper in `finally` on that same actual framework callback thread. Normal returns, invalid-context failures, and the business failure all restore the exact prior state without global scheduler resampling or cross-thread cleanup.
8. Non-reserved Advisor and ToolContext values, concrete `ToolCallingChatOptions`, temperature, tool callback identity, tool definition/schema, tool inputs, exact prior-round tool results, and native response context survive all rounds. All four Actor subjects, correlations, and the reserved key are absent from message contents, tool arguments, schema, and results.
9. ADR 0037 changes only the ticket 09 evidence paragraph to record four-request coverage, forced first-round overlap, and deterministic same-thread restoration. The adapter README and production implementation remain unchanged by the review fix.

## Delivery files

- `framework/adapters/moduvera-spring-ai-context/src/main/java/io/github/ande1922/moduvera/ai/SpringAiExecutionContexts.java`
- `framework/adapters/moduvera-spring-ai-context/src/test/java/io/github/ande1922/moduvera/ai/SpringAiExecutionContextsTest.java`
- `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/SpringAiToolLoopConsumerTest.java`
- `framework/adapters/moduvera-spring-ai-context/README.md`
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`

No root POM/BOM, Kernel, HTTP/Reactor adapter, other ADR, tracker, or formal Business Service file changed.

The review-fix index contains only:

- `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/SpringAiToolLoopConsumerTest.java`
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`

## Verification

Regression-first evidence:

`./mvnw -pl framework/adapters/moduvera-spring-ai-context -am test`

- Expected RED before implementation: test compilation failed because `SpringAiExecutionContexts.toolCallback(ToolCallback)` did not exist.
- Log: `tool-wrapper-regression-red.log`.

Initial and focused tests:

- Adapter green after implementation: `adapter-tests-initial-green.log`.
- Real tool-loop development runs: `ai-tool-loop-tests-first.log`, `ai-tool-loop-tests-second.log`, and passing `ai-tool-loop-tests-third.log`.
- Focused real loop repeat: `./mvnw -pl verification/moduvera-spring-ai-context-consumer -am -Dtest=SpringAiToolLoopConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS, 3 tool-loop tests; log `tool-loop-focused-repeat.log`.

Accepted initial review findings and remediation:

- Standards P2: the concurrent test did not force tool calls to overlap — accepted and fixed with an all-four first-round delegate barrier.
- Standards P2: cleanup depended on 512 probabilistic global bounded-elastic resubmissions — accepted and fixed with a same-call-thread outer callback probe; all resubmission code was removed.
- Spec P1: no different-Tenant concurrent request and no forced overlap — accepted and fixed with Tenant A request 1/request 2, Tenant B, Platform, and the same all-four barrier.
- Independent severity labels remain unchanged; these are one combined evidence fix, not a production defect.

Review-fix focused verification after Clean Code:

`./mvnw -pl verification/moduvera-spring-ai-context-consumer -am -Dtest=SpringAiToolLoopConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`

- PASS, 3 real tool-loop tests, zero failures/errors/skips.
- Final log: `review-fix-focused-final.log`.

Review-fix affected verification:

`./mvnw -pl framework/adapters/moduvera-spring-ai-context,verification/moduvera-spring-ai-context-consumer -am verify`

- PASS: Kernel 43 tests, AI adapter 6 tests, independent AI consumer 8 tests; 57 total, zero failures/errors/skips.
- Final log: `review-fix-affected-verify.log`.

Final affected verification after Clean Code:

`./mvnw -pl framework/adapters/moduvera-spring-ai-context,verification/moduvera-spring-ai-context-consumer -am verify`

- PASS with dependency convergence, compilation, Spotless and PMD on the managed adapter path, tests, package, and JaCoCo reporting.
- Counts: Kernel 43 tests, AI adapter 6 tests, independent AI consumer 8 tests (including 3 real tool-loop tests); 57 total, zero failures/errors/skips.
- Final log: `affected-verify-final.log`.

Dependency checks:

- `./mvnw -pl framework/adapters/moduvera-spring-ai-context -am dependency:tree` — PASS; `ai-adapter-dependency-tree.log`.
- `./mvnw -pl verification/moduvera-spring-ai-context-consumer -am dependency:tree` — PASS; `consumer-dependency-tree.log`.
- `./mvnw -pl framework/foundation/moduvera-kernel -am dependency:tree` — PASS; `kernel-dependency-tree.log`; no Spring AI or Reactor dependency found in Kernel.
- `git diff --cached --check` — PASS.

Resolved runtime versions:

- Java: OpenJDK 26 (`26+35-2893`)
- Maven: 3.9.16
- Spring Boot BOM: 4.1.1
- Spring AI / `spring-ai-client-chat`: 2.0.1
- Reactor Core: 3.8.7
- Spring Framework Core: 7.0.9

## Limits and handoff

- The scripted model is test source and makes no paid or external provider call. It proves the actual local Spring AI 2.0.1 ChatClient/ToolCallingAdvisor/ToolCallback/native-context mechanics, not external model-provider behavior.
- Arbitrary custom Advisor internal async callbacks are outside this adapter's automatic coverage and need explicit propagation. A Scope around `return nextStream(...)` covers Publisher assembly only.
- A direct `spotless:apply` prefix invocation reached the unmanaged verification consumer, attempted Maven plugin-prefix metadata updates, and was blocked from writing the user's Maven cache by the sandbox. The repository-managed source modules had already formatted successfully; final `verify` passed. Log: `spotless-apply.log`.
- Initial independent Standards and Spec reviews completed at `bbb334f1226922889c1cd915f93d9aebe2d5c81e`. The coordinator accepted Standards P2/P2 and Spec P1 with their independent severities retained; this staged follow-up implements their combined disposition. Re-review remains coordinator-owned.
- No formal quality gate, tracker update, integration, rebase, push, paid call, or worktree cleanup was performed.
- Per coordinator direction, the worker staged exactly the two review-fix files and did not attempt the ordinary follow-up commit. The coordinator owns creating that already-authorized commit and should replace the pending review-fix head above with the resulting full SHA.

Coordinator delivery update: worker-staged content was committed without code edits after direct user confirmation. Independent review uses the full base-to-result range; staging statements above describe the historical handoff.
