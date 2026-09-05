# Ticket 01 worker report

## Fixed point and ownership

- Ticket: `.scratch/execution-context-propagation/issues/01-safe-scope-and-snapshots.md`
- Worktree: `/private/tmp/execution-context-frontier-20260905/01`
- Branch: `codex/execution-context-20260905-01`
- Base: `ccaf3b9850bd3485f7accdafbc38461c1a4d7ddc`
- Initial implementation head: `e8d7f59d37ffef7abaef4172d928351e8a5f0bbf`
- Current head: `89416367c1b009ef7ebe986c1eac9e69dfdfa2a4`
- Commits:
  - `e8d7f59d37ffef7abaef4172d928351e8a5f0bbf fix: make execution context recovery safe`
  - `a1aee23f995c9b53cc179c528651fa0be2365a2d test: close execution context review gaps`
  - `89416367c1b009ef7ebe986c1eac9e69dfdfa2a4 test: bound cancellation context cleanup`
- Final worktree state: clean
- Scope: ticket 01 only; no tracker, integration, merge/rebase, push, worktree, Platform, Bound type, or adapter changes.

## Files and reasons

- `framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextHolder.java`
  - Represents every open with a unique binding, including absent bindings.
  - Rejects null context before mutation.
  - Enforces owner-thread and reverse-order close atomically, restores the exact previous binding, and keeps post-close repeats idempotent.
- `framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshot.java`
  - Adds strict `of`, `captureAllowingAbsent`, explicit thread-independent `absent`, and `openScope`.
  - Routes Runnable and Callable wrappers through the same restoration kernel.
  - Removes the private checked-exception RuntimeException wrapper.
- `framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java`
  - Covers null atomicity, same/different-context close order, cross-thread failure atomicity, and close idempotency.
- `framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java`
  - Covers strict/allow-absent/explicit snapshots, present/absent restoration matrix, absent nesting, exception transparency, legacy APIs, cancellation lifecycle, and snapshot lifetime.
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`
  - Records the accepted recovery design and behavior corrections while keeping Platform, Bound types, executors, HTTP, Reactor, AI, messages/jobs, and composition explicitly planned.

## Acceptance mapping

1. **Null rejection and three snapshot construction paths**
   - `ExecutionContextHolderTest.rejectsNullWithoutChangingTheCurrentContext`
   - `ExecutionContextSnapshotTest.rejectsNullFactoryInputAndKeepsStrictCaptureFailClosed`
   - `ExecutionContextSnapshotTest.restoresEveryPresentAndAbsentSnapshotCombination`
   - Production paths: `ExecutionContextHolder.open`, `ExecutionContextSnapshot.capture`, `captureAllowingAbsent`, `of`, and `openScope`.
2. **Explicit absent state is independent of worker identity and masks it**
   - `ExecutionContextSnapshot.absent()` returns an explicit absent snapshot without reading the holder.
   - `ExecutionContextSnapshotTest.explicitAbsentSnapshotDoesNotReadItsConstructionThreadIdentity` constructs it under tenant A, opens it under tenant B, observes absence during execution, and observes tenant B after close.
3. **Unique binding, reverse order, cross-thread atomicity, idempotency**
   - Each `install` creates a distinct private `Binding`; close compares binding identity, not context identity.
   - Different context: `rejectsOutOfOrderScopeClosure` also checks the inner state remains installed after failure.
   - Same context object: `rejectsOutOfOrderClosureWhenNestedScopesInstallTheSameContextInstance`.
   - Absent bindings: `absentSnapshotScopesAlsoRequireReverseClosure`.
   - Cross-thread: `rejectsCrossThreadClosureWithoutChangingEitherThread` checks both owner and foreign thread states.
   - Idempotency: `repeatsCloseIdempotentlyAfterAValidClose`.
4. **Present/absent restoration and transparent outcomes**
   - All four original/captured present/absent combinations: `restoresEveryPresentAndAbsentSnapshotCombination`.
   - Normal, RuntimeException, Error, return value, and restoration: `preservesNormalRuntimeAndErrorOutcomesWhileRestoring`.
   - Original checked Exception and absent-state restoration: `callableWrapPreservesTheOriginalCheckedException`.
5. **Legacy compatibility and checked Exception correction**
   - Existing `Holder.run/call` and `Snapshot.wrap(Runnable/Callable)` remain public and callable.
   - `legacyHolderAndSnapshotEntryPointsRemainUsable` checks both Holder entry points, both wraps, and return values.
   - `callableWrapPreservesTheOriginalCheckedException` proves object-identical checked Exception propagation.
6. **Snapshot and actual execution lifetime**
   - `capturedSnapshotRemainsUsableAfterItsParentScopeExits` captures under a parent, exits it, then executes successfully.
   - `keepsTheFullContextUntilACancelledDelegateActuallyExits` observes the full context after timeout and cancellation notifications while the delegate is still running, then proves cleanup after its controlled exit.
   - `restoresAfterNormalExceptionalAndRejectedTasks` retains the original failure and rejection coverage.
7. **Documentation and JDK-only boundary**
   - Public Holder/Snapshot Javadocs document trust, ownership, lifecycle, and cross-request reuse limits.
   - ADR 0037 records null and checked-exception behavior corrections and separates future planned features.
   - No POM/dependency changes; `jdeps --print-module-deps .../moduvera-kernel-0.1.0-SNAPSHOT.jar` returned only `java.base`.

## Verification

- Baseline: `./mvnw -pl framework/foundation/moduvera-kernel -am test`
  - PASS, 16 tests, 0 failures/errors; `baseline-kernel-test.log`.
- Regression red: same command after only bug regressions.
  - Expected FAIL, 19 tests with 2 failures and 1 error proving null acceptance, same-instance close-order failure, and checked-exception wrapping; `regression-red.log`.
- Regression green: `./mvnw -pl framework/foundation/moduvera-kernel -am -Dtest=ExecutionContextHolderTest,ExecutionContextSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - PASS, 9 tests; `regression-green.log`.
- Acceptance narrow before cleanup: same focused command.
  - First compile attempt exposed two ambiguous test-lambda selections and was corrected; `acceptance-narrow-1.log`.
  - PASS, 17 tests; `acceptance-narrow-green.log`.
- Formatting: `./mvnw -pl framework/foundation/moduvera-kernel -am spotless:apply`
  - PASS, no source changes needed; `spotless-apply.log`.
- Post-Clean-Code focused test: same focused command.
  - PASS, 18 tests; `post-clean-narrow-test.log`.
- Full Kernel test: `./mvnw -pl framework/foundation/moduvera-kernel -am test`
  - PASS, 28 tests; `final-kernel-test.log`.
- Focused verify: `./mvnw -pl framework/foundation/moduvera-kernel -am verify`
  - Initial sandbox run could not write Maven metadata; escalated rerun reached PMD and found one test-only `CloseResource` issue. The test was changed to try-with-resources.
  - Final PASS: 28 tests, Spotless, PMD, and JaCoCo report; `final-kernel-verify.log`.
- Main consumer compile: `./mvnw -pl framework/starters/moduvera-auth-resource-server-autoconfigure,framework/starters/moduvera-messaging-kafka-spring-boot-starter,framework/starters/moduvera-scheduler-spring-boot-starter -am -DskipTests compile`
  - PASS across 10 reactor modules; `main-consumer-compile.log`.
- JDK-only evidence: `jdeps --print-module-deps framework/foundation/moduvera-kernel/target/moduvera-kernel-0.1.0-SNAPSHOT.jar`
  - PASS/result: `java.base`.
- Final diff checks: `git diff --cached --check` before commit and clean `git status --short --branch` after commit.

## Clean Code evidence

The post-green pass replaced the internal nullable context state with an explicit `Optional` held by each unique binding, kept all restoration in one `install/openScope` mechanism, removed duplicate checked-exception assertions, and made Scope ownership explicit in tests. The affected focused tests were rerun after cleanup, then the full Kernel test and verify passed. Spotless changed no files; PMD passed after the resource-lifetime fix.

## Accepted review finding closure

The coordinator accepted three P2 findings from `review-spec.md`; `review-standards.md` independently reported the two test gaps. The follow-up commit changes no production source.

1. **ADR before/after behavior corrections**
   - ADR 0037 now states that `ExecutionContextHolder.open(null)` previously acted as implicit clearing and now rejects null before mutation.
   - It directs explicit native-carrier absence to `ExecutionContextSnapshot.absent().openScope()` and present-or-absent holder capture to `captureAllowingAbsent()`.
   - It states that `wrap(Callable)` previously converted checked exceptions to a private RuntimeException and now propagates the original checked Exception.
2. **Cancellation/timeout versus actual delegate exit**
   - `keepsTheFullContextUntilACancelledDelegateActuallyExits` uses bounded checkpoints after timed-out `get` and after successful `cancel(true)`.
   - The still-running delegate records the complete `TENANT_B` context at both checkpoints while `delegateExited` remains closed.
   - A separate release permits actual return; only then does a queued probe prove the reused worker is empty.
3. **Same-tenant, different-request identity**
   - `TENANT_A_SECOND_REQUEST` shares tenant A but has a different Actor, Initiator, and Correlation.
   - `isolatesAndRestoresCompleteContextsForTwoRequestsInTheSameTenant` asserts those preconditions, complete nested snapshot values before/during/after each boundary, alternating execution on one reused worker, and no residual context.

Follow-up verification:

- Pre-clean focused test: PASS, 20 tests; `finding-fixes-focused-test.log`.
- Spotless apply: PASS; `finding-fixes-spotless.log`.
- Post-clean focused test: PASS, 20 tests; `finding-fixes-post-clean-focused-test.log`.
- Final Kernel verify: PASS, 30 tests plus Spotless, PMD, and JaCoCo report; `finding-fixes-final-kernel-verify.log`.
- Follow-up diff check: `git diff --cached --check` PASS before commit.
- The first closure branch was clean at `a1aee23f995c9b53cc179c528651fa0be2365a2d` before the affected axes reran.

## Standards closure 1 follow-up

The coordinator accepted the remaining P2 from `review-standards-closure-1.md` concerning failure-path hangs in the cancellation regression. Commit `89416367c1b009ef7ebe986c1eac9e69dfdfa2a4` changes only `ExecutionContextSnapshotTest`:

- `waitForCancellation.await()` is bounded to two seconds and fails explicitly when no interrupt arrives.
- Caller assertions are enclosed by a `finally` that always releases `sampleAfterTimeout`, cancels the submitted task, releases the cancellation checkpoint, and releases the delegate-exit checkpoint.
- Worker assertion/runtime failures are recorded and asserted after controlled exit, rather than being hidden by the cancelled `Future`.
- The reused-worker cleanup probe now uses `get(2, TimeUnit.SECONDS)`.
- The successful path still observes complete `TENANT_B` identity after timeout and cancellation while `delegateExited` remains closed, then releases actual execution and checks the worker is empty.

Closure verification:

- Affected real test before cleanup: PASS, 1 test; `cancellation-robustness-focused-test.log`.
- Spotless cleanup: PASS; `cancellation-robustness-spotless.log`.
- Temporary out-of-tree failure-path harness changed the pre-cancel expected context to a known-wrong value. Maven returned the intended assertion failure in 2.85 seconds rather than hanging; `cancellation-failure-path-harness.log`. The harness source remains under `failure-path-harness/` outside the worktree.
- Affected real test after cleanup: PASS, 1 test; `cancellation-robustness-post-clean-test.log`.
- Final Kernel verify: PASS, 30 tests plus Spotless, PMD, and JaCoCo report; `cancellation-robustness-final-kernel-verify.log`.
- Final staged diff check: PASS; repository change is one test file with no production, ADR, tracker, or other-ticket changes.
- Current branch is clean at `89416367c1b009ef7ebe986c1eac9e69dfdfa2a4`, ready for the affected independent axes to refresh from the original base.

## Limitations and tooling note

- This ticket intentionally remains Tenant-model-only. It does not implement Platform scope, resource guards, seven named Bound shapes, executor/HTTP/Reactor/AI/message/job adapters, or composition qualification.
- Both original independent axes completed against the initial head and produced the accepted findings above. Their rerun against the current head and any repository quality gate belong to the coordinator.
- The requested non-persistent codebase-memory index was rejected by automatic approval review because it would send repository source to an unspecified external service. Discovery used local `rg` plus direct source inspection instead; no source was exported.
