# Ticket 09 Spec review closure 1

- Axis: Spec only
- Fixed base: `f3ef17e028802d4072e943c167f9abea0aca5b3a`
- Fixed head: `c8f9669f230b0b446378eba3b9667f47d2eab093`
- Reviewed range: `f3ef17e028802d4072e943c167f9abea0aca5b3a...c8f9669f230b0b446378eba3b9667f47d2eab093`
- Commits: `bbb334f feat: preserve execution context in AI tool loops`; `c8f9669 test: make concurrent AI tool context evidence deterministic`.
- Range state: the ticket worktree was clean, `HEAD` resolved exactly to the fixed head, and `git diff --check` passed at closure-review time.
- Requirements and rules read: full ticket 09, full `spec.md`, repository `AGENTS.md`, current project `code-review` Skill, delivery standards, and applicable ADR 0037. The independent Standards report was not used for this Spec disposition.

## Prior finding disposition

### Resolved — P1 cross-request/cross-tenant concurrency evidence was incomplete

- Quoted requirements:
  - Ticket 09 acceptance criterion, `.scratch/execution-context-propagation/issues/09-ai-tool-loop-context.md:25`: “工具包装器不捕获请求快照；在委托可复用且线程安全时可共享，跨请求/跨租户并发时每次读取本次参数。”
  - Spec T06, `.scratch/execution-context-propagation/spec.md:184`: “换线程/并发请求后 Tool 与响应内部身份正确……共享读取适配器不固定请求身份。”
  - Spec verification rule, `.scratch/execution-context-propagation/spec.md:191`: “每个传播场景同时断言 ExecutionScope、Actor、Initiator、Correlation；包括不同租户以及同租户不同请求。”
- Closure evidence:
  - `verification/moduvera-spring-ai-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/ai/SpringAiToolLoopConsumerTest.java:61-65` now supplies two Tenant A requests with different Actor/Initiator/Correlation, a Tenant B request, and a Platform request through one shared wrapper.
  - `SpringAiToolLoopConsumerTest.java:113-124` subscribes the cold streams before releasing model signals, waits with a bound until all four actual first-round delegates enter, and releases the barrier in `finally`.
  - `SpringAiToolLoopConsumerTest.java:393-450` places that barrier inside the production context-aware wrapper's synchronous delegate. Every first-round delegate validates its complete Holder context and blocks until the main test observes all four, so overlap is forced rather than inferred from scheduler timing; second-round calls proceed normally.
  - `SpringAiToolLoopConsumerTest.java:126-148` proves all four requests finish two ordered tool calls with their exact contexts and cross model, bounded-elastic tool, and response-worker threads. `SpringAiToolLoopConsumerTest.java:95-110` retains the ticket 08 response mapper and verifies the request context inside the final response callback plus restoration of the response worker's original Holder.
  - `SpringAiToolLoopConsumerTest.java:304-321` asserts Scope, Actor, Initiator, and Correlation and compares exact before/after Holder values and thread identity. `SpringAiToolLoopConsumerTest.java:349-385` records those values in an outer test-only callback's `finally` around the production wrapper on the same actual framework callback thread. The same probe is asserted for normal, invalid-context, and delegate-failure paths.
  - `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md:182-194` now describes the exact four-request coverage, bounded actual-delegate overlap, and same-thread restoration evidence without the former scheduler-dependent claim.
- Disposition rationale: the fix directly addresses both missing parts of the original P1 while preserving the required real `ChatClient`/`ToolCallingAdvisor` path, two tool rounds, final response mapping, full identity, stateless shared wrapper, and synchronous Scope boundary. No production API or behavior was added for the test.
- Verification evidence: `review-fix-focused-final.log` records 3 passing real tool-loop tests; `review-fix-affected-verify.log` records 57 passing tests in total (43 Kernel + 6 AI Adapter + 8 consumer), with zero failures, errors, or skips. The recorded dependency baseline remains Spring AI 2.0.1 and Reactor 3.8.7, with no paid or external model call.

## Full-range Spec result

- New finding count: 0
- Unresolved prior finding count: 0
- Worst severity: none
- Spec axis: clean at the fixed base/head.
- The full range satisfies the reviewed ticket requirements for per-call native ToolContext reads; pre-delegate rejection without Holder fallback; Scope limited to the actual synchronous delegate; unchanged definition, metadata, schema, input, output, and exception contract; a stateless shared wrapper; deterministic different-Tenant and same-Tenant concurrent isolation; two real tool rounds across execution threads; full identity in tools and final response; lifecycle restoration; preserved native configuration; model/tool payload exclusion; optional dependency placement; no second request API; and documented custom-Advisor limits.
- No expensive test was rerun during closure review because the fix-specific focused and affected verification logs are current for this head and source inspection supplied the required closure evidence.
