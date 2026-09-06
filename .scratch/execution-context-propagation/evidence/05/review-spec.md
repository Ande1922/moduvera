# Ticket 05 Spec review

Axis: Spec only\
Ticket: `.scratch/execution-context-propagation/issues/05-spring-task-executor-propagation.md`\
Worktree: `/private/tmp/execution-context-frontier-20260905/05`\
Branch: `codex/execution-context-20260905-05`\
Fixed base: `e76da5ab2629196e267c24b88f47e4605661ad95`\
Reviewed head: `42ac607ee40d56a6aeb164771fa0b4328feb3931`\
Result: PASS — 0 findings; worst severity: none\
Requested reviewer profile: `gpt-5.6-sol/high`; effective runtime profile was not exposed and remains unverified.

## Range integrity

The fixed range resolves to one commit and exactly the 11 ticket files described by the worker report. `HEAD` is the reviewed head and `git status --short --branch` reports no repository changes. Ticket 03 commit `8f4cace9078dcb55b737d61ff7ec830f68fe3c6f` is an ancestor of the fixed base. The full committed binary diff has SHA-256 `feafc52f8cb84512a2f26701777c4ed6bfba506a169d31912da1c271fcfdd394`, exactly matching `staged.patch`, so the coordinator commit contains the original worker patch without content changes. `git diff --check` passes for the full range.

## Requirement disposition

No missing, partial, incorrectly implemented, or out-of-scope behavior was found.

- Requirement: “TaskDecorator 在每次实际任务装饰/提交时允许缺失捕获，复用具名绑定与统一 Scope；不在 Bean 创建时捕获请求身份。” `ExecutionContextTaskDecorator.decorate` performs `captureAllowingAbsent().bindRunnable(runnable)` at decoration time and stores no identity (`framework/adapters/moduvera-spring-task-context/src/main/java/io/github/ande1922/moduvera/context/spring/ExecutionContextTaskDecorator.java:6-20`). Focused tests cover per-decoration present and absent capture, restoration under a different inline identity, exact delegate failure, and null rejection (`ExecutionContextTaskDecoratorTest.java:22-89`).
- Requirement: “显式配置选定 TaskExecutor，真实 @Async 调用经过该执行器时传播完整上下文；非选定执行器不被全局劫持。” The independent consumer explicitly configures one decorated and one plain `ThreadPoolTaskExecutor` (`SpringTaskContextConsumer.java:9-41`), and real proxy methods name the two executors with `@Async` (`AsyncContextProbe.java:9-25`). The consumer test proves selected propagation after parent-Scope exit and plain-executor absence (`SpringTaskContextConsumerTest.java:53-101`).
- Requirement: “Platform/Tenant/缺失以及同租户不同请求在复用线程上正确隔离；原来有身份的 inline/CallerRuns 路径退出后恢复。” A single selected worker observes two complete same-tenant but different Actor/Initiator/Correlation/permission values, Platform, and absence, then is checked for no residual context (`SpringTaskContextConsumerTest.java:53-89`). A separate real worker starts with a Platform identity, has absent submissions mask it, and recovers it when the worker loop exits (`SpringTaskContextConsumerTest.java:103-136`). Real `CallerRunsPolicy` execution preserves the exact failure and restores the submitting identity (`SpringTaskContextConsumerTest.java:167-201`); the adapter test separately proves restoration when captured and invoking identities differ.
- Requirement: “原 Spring 任务返回、异常、拒绝、取消与生命周期行为保持” and “父 Scope 退出不自动使已提交任务失效；真实退出处清理.” The real Spring executor tests cover returned values, exact Future causes, Future-returning `@Async` failure, shutdown rejection, cancellation before start, running cancellation, timed observation, cleanup only after actual delegate exit, and ApplicationContext-owned shutdown (`SpringTaskContextConsumerTest.java:138-165,203-325`). Latch-based coordination is bounded and does not use completion callbacks to close worker state.
- Requirement: “配置与 public consumer 测试证明无需 04；Kernel 不引入 Spring，使用方不被迫引入 Reactor/AI.” Production uses ticket 03's public Snapshot/binding API and contains no `ContextExecutors` reference. The adapter depends only on Kernel and Spring Core (`framework/adapters/moduvera-spring-task-context/pom.xml:15-24`). The standalone consumer inherits the Spring Boot parent, imports the public Moduvera BOM, and declares the adapter without a version (`verification/moduvera-spring-task-context-consumer/pom.xml:5-43`). Its recorded runtime closure contains Kernel, Spring Core/Context and ordinary Spring dependencies, with no Reactor or Spring AI.
- Requirement: “接入说明明确 TaskDecorator 与显式回调绑定的分工、@Async 实际执行器前提及状态缺失规则.” The adapter README states selection, decoration-time capture, Tenant/Platform/explicit-absence behavior, parent/cancellation/timeout lifetime, actual-exit restoration, callback registration binding, Future exception-observation limits, and unsupported executor/security/transaction/observability/scheduler surfaces (`framework/adapters/moduvera-spring-task-context/README.md:3-44`). ADR 0037 changes only the allocated ticket 05 section and makes the same bounded claim (`docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md:139-169`). The repository Product Surface remains broader and Planned, so this ticket does not overstate complete cross-model support.
- Scope: the optional adapter, its focused tests, the external-parent public consumer, required root/BOM registration, README, and the allocated ADR section are necessary to implement and prove ticket 05. The diff does not add global decoration, scheduling, Reactor, AI, security, transaction, MDC, tracing, raw-thread, common-pool, or ticket 04 executor behavior.

## Current evidence and limits

The exact-range worker log records `BUILD SUCCESS` for the affected five-module verify: Kernel 52 tests, adapter 4 tests, and consumer 6 tests, all with zero failures, errors, or skips. Adapter Enforcer/dependency convergence, Spotless, PMD, JaCoCo, compilation, and packaging passed; the external-parent consumer compiled, tested, packaged, and produced its JaCoCo report. The saved dependency tree resolves Spring Framework 7.0.9 under Spring Boot 4.1.1 and Java 26 and contains no Reactor or Spring AI. The recorded JaCoCo summaries cover every production line in the one-class adapter and the two consumer fixture classes.

I did not rerun the already-passing Maven checks because the patch hash proves the supplied evidence applies to the exact committed range and inspection found no new concern requiring another execution. This is an independent Spec-axis review only. It did not use the ticket 05 Standards report, run the repository quality gate, edit source/tracker files, or assess integration state beyond the fixed worker range.
