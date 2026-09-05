Ticket: `.scratch/execution-context-propagation/issues/05-spring-task-executor-propagation.md` — Spring TaskExecutor 接入
Worktree: `/private/tmp/execution-context-frontier-20260905/05`
Branch: `codex/execution-context-20260905-05`
Base: `e76da5ab2629196e267c24b88f47e4605661ad95`
Required dependency: ticket 03 commit `8f4cace9078dcb55b737d61ff7ec830f68fe3c6f` is an ancestor of the base
Result: original worker patch committed by coordinator at `42ac607ee40d56a6aeb164771fa0b4328feb3931`; clean checkout
Staged patch SHA-256: `feafc52f8cb84512a2f26701777c4ed6bfba506a169d31912da1c271fcfdd394`
Staged patch: `/private/tmp/execution-context-frontier-20260905/evidence/05/staged.patch`

## Acceptance evidence

- Per-submission capture and core reuse: `ExecutionContextTaskDecorator.decorate` calls `ExecutionContextSnapshot.captureAllowingAbsent().bindRunnable(runnable)` for every Spring decoration. Construction captures no identity. Adapter tests prove decoration-time capture, explicit absence, inline restoration, exact failure propagation, and null rejection.
- Explicit executor and real Async route: the independent consumer starts a real `AnnotationConfigApplicationContext`, configures a selected single-worker `ThreadPoolTaskExecutor` with the public decorator, configures a separate plain executor, and invokes both through actual `@Async` proxy methods. The selected route propagates; the plain route remains absent.
- Full isolation: consumer tests compare the entire immutable `ExecutionContext`, including Tenant or Platform scope, Actor type/subject/permissions, Initiator type/subject, and correlation. They cover same-tenant requests with different identities, selected worker reuse, an absent submission masking a worker-lifecycle Platform identity, and exact restoration after the worker loop exits.
- Lifecycle independence: a selected Async call is queued behind a bounded latch, its submitting parent Scope exits, and the later worker call still sees the captured full context. Cancellation and timed Future observation leave the running worker context installed until the delegate actually exits; cleanup is then observed by a subsequently queued task on that worker. No completion listener or foreign thread closes a worker Scope.
- Native task behavior: real Spring executors preserve submitted values and exact Future causes; real `@Async` Future failure is observed through its Future; shutdown rejection remains `TaskRejectedException` with native `RejectedExecutionException`; cancellation before start suppresses the delegate; cancellation during execution retains context through actual exit; ApplicationContext close owns executor shutdown. A saturated real `ThreadPoolTaskExecutor` with `CallerRunsPolicy` preserves the same thrown object and restores the inline caller.
- Dependency and composition: the new optional `moduvera-spring-task-context` Adapter depends only on `moduvera-kernel` and Spring Core. The independent consumer uses the public BOM and Spring Context, not the internal Parent. Runtime dependency output contains no Reactor or Spring AI; production and consumer sources contain no `ContextExecutors` reference. The root reactor and BOM register the new adapter, and the root reactor registers the consumer.
- Documentation: the adapter README documents selected-executor configuration, actual `@Async` routing, absence behavior, parent/cancel/timeout lifecycle, registration-time Future callback binding, Future exception-observation limits, and unsupported global/security/transaction/scheduler behavior. Only the ticket 05 section of ADR 0037 is changed.

## Changed files

- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`: records the verified ticket 05 Spring boundary and evidence.
- `framework/adapters/moduvera-spring-task-context/README.md`: dedicated public usage and lifecycle limits.
- `framework/adapters/moduvera-spring-task-context/pom.xml`: optional adapter artifact with Kernel and Spring Core dependencies.
- `framework/adapters/moduvera-spring-task-context/src/main/java/io/github/ande1922/moduvera/context/spring/ExecutionContextTaskDecorator.java`: public reusable TaskDecorator.
- `framework/adapters/moduvera-spring-task-context/src/test/java/io/github/ande1922/moduvera/context/spring/ExecutionContextTaskDecoratorTest.java`: focused public adapter tests.
- `framework/bom/pom.xml`: BOM management for the new adapter.
- `pom.xml`: reactor registration for the adapter and independent consumer.
- `verification/moduvera-spring-task-context-consumer/pom.xml`: external-Parent, BOM-managed consumer with JaCoCo 0.8.15 report wiring.
- `verification/moduvera-spring-task-context-consumer/src/main/java/io/github/ande1922/moduvera/verification/springtask/AsyncContextProbe.java`: real selected/plain Async proxy methods and Future failure method.
- `verification/moduvera-spring-task-context-consumer/src/main/java/io/github/ande1922/moduvera/verification/springtask/SpringTaskContextConsumer.java`: explicit selected and unselected executor configuration.
- `verification/moduvera-spring-task-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/springtask/SpringTaskContextConsumerTest.java`: bounded real Spring integration and lifecycle evidence.

## Verification

- `git status --short --branch && git rev-parse HEAD && git merge-base --is-ancestor 8f4cace9078dcb55b737d61ff7ec830f68fe3c6f HEAD`: clean start, exact base `e76da5ab2629196e267c24b88f47e4605661ad95`, ancestor exit 0.
- `./mvnw -pl verification/moduvera-spring-task-context-consumer -am test`: exit 0 after correcting the test fixture; Kernel 52 tests, adapter 4 tests, consumer 6 tests, all zero failures/errors/skips. The earlier fixture attempted to leave a child Scope open inside Spring's decorated native pool and correctly triggered the Kernel reverse-close guard; it was replaced with a worker-lifecycle identity fixture and was not a production defect.
- `./mvnw -l /private/tmp/execution-context-frontier-20260905/evidence/05/affected-verify.log -pl framework/bom,framework/adapters/moduvera-spring-task-context,verification/moduvera-spring-task-context-consumer -am verify`: exit 0; five-module reactor including current BOM; 62 tests total (Kernel 52, adapter 4, consumer 6), zero failures/errors/skips; adapter Enforcer, Spotless, PMD, and JaCoCo passed; both adapter and consumer JaCoCo reports were generated.
- `./mvnw -pl verification/moduvera-spring-task-context-consumer -am dependency:tree -Dscope=runtime -DoutputType=text -DoutputFile=/private/tmp/execution-context-frontier-20260905/evidence/05/runtime-dependencies.txt`: exit 0; Spring Framework 7.0.9, Kernel, Spring Core/Context and their ordinary dependencies; no Reactor or Spring AI.
- JaCoCo 0.8.15: `ExecutionContextTaskDecorator` 0 missed / 2 covered lines; `SpringTaskContextConsumer` 0 missed / 12 covered lines; `AsyncContextProbe` 0 missed / 4 covered lines. Copies: `adapter-jacoco.csv`, `consumer-jacoco.csv` in this evidence directory.
- `./mvnw -version`: Apache Maven 3.9.16; Oracle Java 26; macOS aarch64. Spring Boot Parent/BOM baseline is 4.1.1; resolved Spring Framework is 7.0.9.
- `git diff --cached --check`: exit 0.
- Final repository status: all 11 ticket files staged and no unstaged repository edits.

## Risks or limitations

- Coverage is deliberately limited to explicitly configured Spring TaskExecutor instances and the Async proxy routes that name them. Raw threads, the common pool, unselected executors, Spring Security context, transactions, MDC/tracing, and scheduler products remain outside this adapter.
- Spring may decorate an internal Future task; Future-returning submission/Async failures must be observed through the returned Future. The adapter does not claim uncaught-handler visibility for every Future failure.
- This worker did not run formal dual-axis review, the repository-wide quality gate, integration, tracker updates, commit, push, or worktree cleanup, per the assigned boundary.

Coordinator delivery update: worker-staged content was committed without code edits after direct user confirmation. Independent review uses the full base-to-result range; staging statements above describe the historical handoff.
