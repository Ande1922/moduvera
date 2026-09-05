# Ticket 08 worker report

- Branch: `codex/execution-context-20260905-08`
- Base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Required prerequisite: `4313748aac07b06943072f7d00f71ce8af5751d0` (confirmed ancestor)
- Head: `0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7`
- Commits:
  - `d5db8666882eeb7f3cc09cf641674cf44df59428 feat: propagate execution context through spring ai`
  - `0e5c6dba5a8895173e66ac6d897c7c56a9bcb6b7 fix: reject conflicting spring ai request context`
- Final worktree: clean

## Delivered criteria

- Added optional `io.github.ande1922.moduvera:moduvera-spring-ai-context`; Spring AI and Reactor remain outside Kernel and protocol-neutral Service APIs.
- `SpringAiExecutionContexts.captureRequest(...)` performs one strict Holder capture and returns one fixed request object. Its immutable Advisor and ToolContext maps retain caller entries and hold the exact same captured `ExecutionContext` under `EXECUTION_CONTEXT_KEY`. Missing Holder state and different reserved values fail before delegation.
- `RequestContext.applyTo(...)` adds non-reserved configuration plus a request-scoped highest-precedence Advisor. The Advisor inspects the fully assembled `ChatClientRequest` and concrete `ToolCallingChatOptions`, rejects a different effective reserved value from client defaults, direct Advisor/ToolContext entries, or request/default options, and then injects the captured object. A request without the native ToolCallingChatOptions carrier fails before model delegation.
- Regression coverage proves six effective collision surfaces fail with zero scripted-model calls. Matching reserved values remain valid while default, direct and adapter Advisor/tool entries, messages, temperature, and concrete options remain intact. An unbound follow-up request on the same shared ChatClient contains no prior identity.
- `SpringAiExecutionContexts.responseMapper(...)` is stateless. Every call reads the current `ChatClientResponse`, rejects missing/wrong-type reserved context before the delegate, opens a Scope only during the synchronous function, and restores the full prior Holder after success or failure.
- The independent consumer subscribes through real `ChatClient.stream().chatClientResponse()`. Its test-only scripted model gates signals after subscription, emits on a dedicated model scheduler, moves response callbacks to bounded worker threads carrying a foreign Holder context, and proves concurrent Tenant, same-Tenant/different-identity, and Platform isolation.
- Consumer assertions preserve Advisor/tool settings and temperature, observe exact native context identity, retain `ChatClientResponse`, and verify context data is absent from prompt text, tool callback/schema surface, and scripted result content.
- ADR 0037 changed only the `AI request and streaming response context — ticket 08` section. The adapter README records reserved-key, response-lifecycle, shared-mapper, request-reuse, content-string, and unsupported Advisor/tool-loop boundaries.

## Resolved runtime versions

- Java: 26 (Oracle OpenJDK)
- Maven: 3.9.16
- Spring Boot BOM: 4.1.1
- Spring AI BOM / `spring-ai-client-chat`: 2.0.1
- Resolved Reactor Core: 3.8.7
- Resolved Spring Framework Core: 7.0.9

## Verification

`./mvnw -pl framework/adapters/moduvera-spring-ai-context,verification/moduvera-spring-ai-context-consumer -am verify`

- PASS after accepted P1 fix: dependency convergence, compilation, Spotless, PMD 7.26.0, Kernel 37 tests, adapter 4 tests, independent consumer 5 tests.
- Final log: `affected-verify-after-p1-final.log`
- Regression-first red log: `collision-regression-red.log` (the original implementation reached the model instead of throwing).

`./mvnw -pl framework/adapters/moduvera-spring-ai-context -am dependency:tree`

- PASS: adapter resolves Spring AI 2.0.1, Reactor 3.8.7, and Spring Framework 7.0.9.
- Log: `ai-adapter-dependency-tree.log`

`./mvnw -pl verification/moduvera-spring-ai-context-consumer -am dependency:tree`

- PASS: independent BOM consumer resolves the public adapter without a local version declaration.
- Log: `independent-consumer-dependency-tree.log`

`./mvnw -pl framework/foundation/moduvera-kernel -am dependency:tree`

- PASS: Kernel dependency tree contains no Spring AI or Reactor dependency.
- Log: `kernel-dependency-tree.log`

`./mvnw -pl framework/testing/moduvera-bom-smoke -am dependency:tree '-Dincludes=org.springframework.ai:*'`

- PASS: existing non-AI BOM consumer has no Spring AI dependency.
- Log: `non-ai-consumer-ai-filter.log`

`git diff --check HEAD^ HEAD`

- PASS.

No formal quality gate, tracker update, integration, rebase, push, or paid/provider model call was performed.

## Ticket 09 API and fixture handoff

- Reuse artifact `io.github.ande1922.moduvera:moduvera-spring-ai-context`.
- Reuse public key `SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY` for `ToolContext.getContext()` validation.
- Reuse `SpringAiExecutionContexts.captureRequest(...)` and `RequestContext.applyTo(...)`; it now owns effective native-carrier validation and injection through a request-scoped Advisor. `advisorContext()` / `toolContext()` remain immutable public output maps containing the same captured object. Do not create a second request capture or a shared default identity.
- Tool-loop requests must expose concrete `ToolCallingChatOptions`; the adapter preserves their concrete/provider settings with the public `mutate()` contract and fails before the model when no native ToolContext carrier exists.
- Reuse and extend test-source fixture `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/ScriptedChatModel.java`. Its response-script constructor accepts `Function<Prompt, ChatResponse>`, records every prompt, gates expected model calls, and emits on a supplied Scheduler, so ticket 09 can script multi-round tool responses without publishing a production model.
- Ticket 09 still owns ToolCallback wrapping, wrong/missing ToolContext rejection, definition/metadata preservation, and real multi-round `ToolCallingAdvisor` evidence.
