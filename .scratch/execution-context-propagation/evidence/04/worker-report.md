# Ticket 04 worker report

- Worktree: `/private/tmp/execution-context-frontier-20260905/04`
- Branch: `codex/execution-context-20260905-04`
- Fixed base: `5e687cfdb21d2d9dc55be42a5ffb93a59b15c452`
- Prerequisite 03: `8f4cace9078dcb55b737d61ff7ec830f68fe3c6f` (confirmed ancestor)
- Head: `269116daf70afb277913c3613ecadd1311a92231`
- Commits:
  - `be0646ef553d85170052fa04abef2c97dc0b20c0 feat: propagate execution context through JDK executors`
  - `269116daf70afb277913c3613ecadd1311a92231 test: prove bulk executor context propagation`
- Final checkout: clean

## Delegation strategy

`ContextExecutors.propagating(Executor)` captures `captureAllowingAbsent()` inside every
`execute` call and passes one BoundRunnable to the selected delegate. The ExecutorService
decorator implements every ExecutorService method and calls the corresponding delegate method:
`execute` and each `submit` bind one task at the direct call; `invokeAll` and `invokeAny` bind
each supplied Callable before forwarding the bulk call. Decorator construction captures
nothing.

The delegate creates and returns its own Futures. `shutdown`, `shutdownNow`, lifecycle queries,
`awaitTermination`, and `close` forward directly; `shutdownNow` returns the delegate's exact
queued-task list. No reflection or task unwrapping is used. A previously Bound callback nests
inside the submission binding, so its original fixed snapshot wins only during its own call and
both scopes restore in normal reverse order. Cleanup belongs to actual delegate exit, including
after timeout, cancellation, or delayed interrupt handling.

## Acceptance evidence

Kernel tests use real single-worker platform pools, `newVirtualThreadPerTaskExecutor`, inline
execution, and a saturated ThreadPoolExecutor with `CallerRunsPolicy`. They cover distinct
tenants, same-tenant fully distinct Actor/Initiator/correlation, Platform, absent masking, and
restoration to a worker's pre-existing context. Original and decorated executors are compared for
submit values, Runnable results, RuntimeException causes, `invokeAll` values/failures/timeouts,
`invokeAny` values/all-failed/timeout, and rejection. Separate cases prove cancellation before
start, timeout without cleanup, cancellation while running, delayed interrupt response, cleanup
only after actual exit, native FutureTask nesting, Bound identity precedence, exact delegate
queued Future return from `shutdownNow`, orderly shutdown, await termination, and close.

All coordination uses bounded CountDownLatch waits or native timed Future/bulk operations; no
fixed sleep is used as proof. The Notes consumer demonstrates that direct task submission captures
the request for that task, while an unbound Future callback later observes its completion thread
and a registration-bound callback observes the request. No Notes security configuration changed.

Independent Standards and Spec reviews of `be0646e` each reported one accepted P2: the four bulk
overloads had native outcome coverage but no task-body context observation. The closure test calls
untimed and successfully timed `invokeAll` and `invokeAny` through the public decorator. It proves
Tenant, Platform, full same-tenant/different-identity, and explicitly absent submissions on one
context-bearing worker, and verifies that same worker restores its original context after every
bulk call. A temporary mutation removing `bindAll` wrapping made this test fail by observing
`WORKER` instead of `REQUEST_A`; the mutation was fully restored before green verification.

Kernel production imports only JDK types and existing Kernel context types. `jdeps` reports
`java.base`; no framework dependency or POM/BOM change was added.

## Commands and results

- `./mvnw -pl framework/foundation/moduvera-kernel -am test` baseline: PASS, 43 tests
  (`01-kernel-baseline.log`).
- `./mvnw -pl framework/foundation/moduvera-kernel -am test` after implementation: PASS, 51
  tests (`04-kernel-narrow-green.log`).
- Clean Code: scoped Kernel Spotless completed with no formatting changes after final cleanup
  (`13-kernel-final-spotless.log`). A combined Kernel + Notes Spotless invocation failed because
  `simple-notes-demo` does not expose the Spotless prefix; the Kernel portion succeeded. This was
  replaced by the supported scoped Kernel invocation.
- `./mvnw -pl framework/foundation/moduvera-kernel -am verify`: PASS, 51 tests; Spotless, PMD,
  compilation, tests, packaging, and JaCoCo report completed (`14-kernel-final-verify.log`). An
  earlier verify exposed only PMD CloseResource findings in lifecycle-focused tests; the test
  class documents/suppresses that deliberate aliasing and the final verify is clean.
- `./mvnw -pl examples/simple-notes-demo -am -Dtest=JdkExecutorContextConsumerTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS, consumer test 1/1
  (`08-notes-consumer-test.log`).
- `./mvnw -pl examples/simple-notes-demo -am -DskipITs verify`: PASS across 11 affected reactor
  modules; Notes unit/consumer tests 3/3 (`15-notes-final-affected-verify.log`). Integration tests
  were intentionally skipped for this JDK-only ticket.
- `jdeps --print-module-deps framework/foundation/moduvera-kernel/target/moduvera-kernel-0.1.0-SNAPSHOT.jar`:
  `java.base` (`16-kernel-jdk-dependency-audit.log`).
- Review closure narrow test with the real implementation: PASS, 9/9 executor tests
  (`17-bulk-context-narrow-green.log`).
- Temporary mutation removing bulk binding: expected RED, 1/9 failure showing the context-bearing
  worker leaked into the bulk task (`18-bulk-context-mutation-red.log`). Production was immediately
  restored; the restored narrow test passed 9/9 (`19-bulk-context-restored-green.log`).
- Review closure Clean Code/Spotless: PASS (`20-bulk-fix-spotless.log`).
- Review closure `./mvnw -pl framework/foundation/moduvera-kernel -am verify`: PASS, 52 tests with
  Spotless, PMD and JaCoCo (`21-bulk-fix-kernel-verify.log`).
- Review closure Notes public consumer check: PASS, 1/1 consumer test through the 11-module reactor
  (`22-bulk-fix-consumer-check.log`).
- The worker's first fix-commit escalation was automatically denied because the reviewer did not
  recognize the explicit commit authority. No bypass or retry was attempted. The coordinator
  verified the exact staged one-test-file diff and cited the user-approved execution-plan item 4;
  the ordinary commit was then approved and created as `269116d` without further source edits.
- `git diff --cached --check`: PASS before each commit; final `git status --short --branch` is clean
  at the head above.

## Limits

Only explicitly decorated Executor or ExecutorService instances propagate. Raw Threads,
ForkJoin/commonPool, scheduled-executor product behavior, Spring TaskExecutor, HTTP, Reactor, and
AI adapters remain outside ticket 04. Executor submission capture covers the submitted task, not
Future/SDK callback registration or asynchronous work started inside a task. No formal review,
quality gate, integration, tracker update, rebase, push, or worktree operation was performed.
