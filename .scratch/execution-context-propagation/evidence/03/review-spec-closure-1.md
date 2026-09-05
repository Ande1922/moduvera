# Ticket 03 Spec review — closure 1

## Fixed point and disposition

- Axis: Spec only
- Worktree: `/private/tmp/execution-context-frontier-20260905/03`
- Branch: `codex/execution-context-20260905-03`
- Fixed base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Reviewed head: `a1b0aaccb421d81f7871c571bba3c5a8704d1351`
- Finding-closure delta: `ce8bd1fdeed59cc388d3369ae9296af760bb5c32...a1b0aaccb421d81f7871c571bba3c5a8704d1351`
- Closure commit: `a1b0aac test: prove sdk callback registration context`
- Checkout at review: clean; `HEAD` equals the reviewed head.
- Result: clean. The prior P2 is resolved, with no new Spec findings in the affected delta. The full fixed-base/new-head Spec axis has 0 open findings.

## Accepted P2 closure

**Requirement:** Ticket 03 requires, “SDK 形状的测试驱动只模拟注册/触发边界，不声称兼容所有供应商；长期监听示例从测试提供的可信事件入口信息逐次重建。” (`.scratch/execution-context-propagation/issues/03-request-bound-callbacks.md:34`).

**Resolution evidence:**

- `CallbackSdkDriver.register` retains the public named `BoundFunction<String, Observation>` callback, and `trigger` invokes it through a separate later boundary (`examples/simple-notes-demo/src/test/java/io/github/ande1922/moduvera/example/notes/RequestBoundCallbackConsumerTest.java:87-98`).
- The shared ordinary `Function` is independently bound during `FIRST_REQUEST` and `SECOND_REQUEST` registration scopes. Both scopes have exited before triggering, as shown by the intervening absent-Holder assertion (`RequestBoundCallbackConsumerTest.java:32-42`).
- Both requests use the same Tenant while Actor, Initiator and Correlation differ (`RequestBoundCallbackConsumerTest.java:24-25,75-80`). Later triggers run under the foreign `WORKER` Platform context, observe the exact registered request identities, and restore `WORKER` after each invocation (`RequestBoundCallbackConsumerTest.java:44-52`).
- The separate long-lived listener remains unbound and rebuilds a binding from each trusted event (`RequestBoundCallbackConsumerTest.java:55-73,100-113`). This preserves the required contrast between request-owned callback registration and per-event reconstruction.
- ADR 0037 now cites the SDK-shaped registration/later-trigger driver and the separate per-event listener without expanding support to vendor-specific SDKs (`docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md:104-109`).
- The focused independent-consumer reactor passed both before and after cleanup: 2 tests, 0 failures/errors/skips, all 11 modules successful (`11-public-consumer-sdk-initial.log`, `12-public-consumer-sdk-post-clean.log`).

## Full-range disposition

The earlier review already confirmed the seven public named JDK shapes, fixed-snapshot restoration, absent masking, checked/Runtime/Error transparency, completed/external/explicit-Executor CompletableFuture behavior, same-tenant full-identity isolation, parent-Scope exit, retry/repeat use, `thenCompose` limits, per-event listener handling, public consumer use and Kernel `java.base` dependency boundary. The closure delta changes only the independent consumer test and the ticket-owned ADR evidence sentence; it introduces no new implementation or support surface.

No broad rebuild was repeated because the accepted finding concerned the missing independent-consumer scenario and the supplied pre/post-cleanup focused executions exercise that exact changed test. This review did not read or depend on the Standards-axis report and made no repository or tracker edits.
