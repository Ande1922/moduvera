# Ticket 03 worker report

## Fixed point and result

- Worktree: `/private/tmp/execution-context-frontier-20260905/03`
- Branch: `codex/execution-context-20260905-03`
- Base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Required ticket 02 ancestor: `4313748aac07b06943072f7d00f71ce8af5751d0` (`git merge-base --is-ancestor` exit 0 before and after implementation)
- Head: `a1b0aaccb421d81f7871c571bba3c5a8704d1351`
- Commits:
  - `ce8bd1fdeed59cc388d3369ae9296af760bb5c32 feat: add request-bound context callbacks`
  - `a1b0aaccb421d81f7871c571bba3c5a8704d1351 test: prove sdk callback registration context`
- Final checkout: clean (`git status --short --branch` printed only the branch header)

## Accepted review fix

The first Spec review at `ce8bd1fdeed59cc388d3369ae9296af760bb5c32` found one P2: the independent consumer invoked bound Functions directly and did not show an SDK-shaped registration method retaining callbacks for a distinct later trigger. The accepted fix adds a test-only `CallbackSdkDriver` whose `register` method retains public named `BoundFunction` values created under two same-tenant request contexts. Its separate `trigger` method runs after both parent Scopes exit while a foreign Platform worker context is installed. The test asserts each complete captured request context and worker restoration after each trigger. The distinct long-lived listener still rebuilds its binding from every trusted event.

## Acceptance criteria mapping

1. Seven immutable named JDK-compatible types: `ExecutionContextSnapshot` exposes `bindRunnable`, `bindCallable`, `bindSupplier`, `bindFunction`, `bindConsumer`, `bindBiFunction`, and `bindBiConsumer`. Their public nested `final` types have private constructors and final state, and implement the corresponding JDK interfaces. `ExecutionContextBoundCallbackTest.exposesAllNamedJdkShapesWithOneFixedSnapshotAndRestoresTheCaller` compiles and executes every shape.
2. Fixed snapshot and restoration: every invocation opens the stored snapshot immediately around a direct delegate call. Kernel tests cover values, the original RuntimeException, Error and checked Exception, caller restoration, and an absent binding masking an existing Platform worker identity.
3. Real CompletableFuture boundaries: kernel tests cover an already-completed inline stage, stages completed on an external context-bearing worker, and `thenApplyAsync` with an explicitly supplied native Executor. Every callback observes the registration-time full context; strict `capture()` fails immediately when the Holder is absent.
4. Lifetime and reuse: callbacks execute after their parent Scope exits, the same binding fails once and succeeds on retry, and two requests with the same Tenant but distinct Actor, Initiator and Correlation retain their exact full values. Child callback scopes restore the worker/parent binding.
5. Visible ownership boundary: Javadoc and the ticket 03 ADR section state that ordinary functions may be shared while Snapshot/Bound objects belong to one logical execution and cannot be cached or reused across requests, including the same Tenant. The independent consumer test reuses one ordinary Function while creating separate request bindings.
6. Composition/listener boundary: `thenComposeBindingEndsWhenTheSynchronousCallbackReturns` proves the bound Function sees the request while the returned async stage uses its own worker context. `RequestBoundCallbackConsumerTest.longLivedListenerRebuildsOneBindingFromEachTrustedEvent` keeps the listener unbound and constructs a fresh binding from each trusted event.
7. Compatibility and direct invocation: legacy Runnable/Callable `wrap` methods return the corresponding named binding. Each Bound implementation calls its original delegate directly inside the Scope; it creates no per-invocation capture lambda and makes no zero-allocation claim.
8. Independent consumer and dependency evidence: the `simple-notes-demo` test compiles/runs only through public Kernel APIs. Maven's compile dependency tree contains only the Kernel artifact root, and `jdeps --print-module-deps` reports `java.base`.

## Changed files

- `framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshot.java`
- `framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextBoundCallbackTest.java`
- `examples/simple-notes-demo/src/test/java/io/github/ande1922/moduvera/example/notes/RequestBoundCallbackConsumerTest.java`
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md` — only `Request-bound callbacks — ticket 03` changed.

No POM, root/BOM, Notes security configuration, executor decorator, global hook, tracker, other ticket section, integration branch, or worktree state was changed.

## Commands and current evidence

- `./mvnw -pl framework/foundation/moduvera-kernel -am test` — PASS, 43 tests, 0 failures/errors/skips. Initial and final narrow runs are in `01-kernel-initial.log`, `03-kernel-post-clean.log`, and `07-kernel-after-pmd-fix-test.log`.
- `./mvnw -pl framework/foundation/moduvera-kernel spotless:apply` — PASS; formatting reported no production/test changes needed. Logs: `02-kernel-spotless-apply.log`, `06-kernel-spotless-after-pmd.log`.
- Initial `./mvnw -pl examples/simple-notes-demo -am -Dtest=RequestBoundCallbackConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test` — PASS across the 11-module consumer reactor; focused consumer ran 2 tests with 0 failures/errors/skips. Log: `04-public-consumer-test.log`.
- First `./mvnw -pl framework/foundation/moduvera-kernel -am verify` — FAIL after all 43 tests passed because PMD did not recognize the bounded executor shutdown helper at two declarations. The complete failure is retained in `05-kernel-verify.log`. The tests already used `finally`, `shutdownNow`, and a two-second `awaitTermination`; focused PMD suppressions now document that deliberate bounded cleanup.
- Final `./mvnw -pl framework/foundation/moduvera-kernel -am verify` — PASS, 43 tests plus Enforcer, Spotless, PMD and JaCoCo report generation. Log: `08-kernel-verify-after-pmd-fix.log`.
- `./mvnw -pl framework/foundation/moduvera-kernel dependency:tree -Dscope=compile` — PASS and printed no compile dependency beneath the Kernel root. Log: `09-kernel-compile-dependencies.log`.
- `jdeps --print-module-deps framework/foundation/moduvera-kernel/target/moduvera-kernel-0.1.0-SNAPSHOT.jar` — PASS, output `java.base`.
- `git diff --check HEAD^ HEAD` — PASS.
- Accepted Spec fix, pre-cleanup focused consumer command above — PASS, 2 tests with 0 failures/errors/skips. Log: `11-public-consumer-sdk-initial.log`.
- Accepted Spec fix, post-cleanup focused consumer command above — PASS, 2 tests with 0 failures/errors/skips; all 11 reactor modules succeeded. Log: `12-public-consumer-sdk-post-clean.log`.

## Limits

- No formal dual-axis review or repository quality gate was run; those are the next coordinator-owned stages.
- No full reactor `clean verify`, infrastructure Scenario, or Testcontainers suite was run because ticket 03 changes only the Java-base Kernel callback surface and its isolated public consumer test.
- The API does not detect cross-request caching at runtime. `thenCompose` does not propagate into the returned stage, and long-lived listeners must rebuild context per trusted event as documented and tested.
