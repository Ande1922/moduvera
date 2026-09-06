# Ticket 11 Standards review

## Fixed point and reviewer profile

- Axis: Standards only.
- Ticket: `.scratch/execution-context-propagation/issues/11-composition-and-consumer-qualification.md`.
- Worktree: `/private/tmp/execution-context-frontier-20260905/11`.
- Branch: `codex/execution-context-20260905-11`.
- Fixed base: `04c66e84e4b05814e462ba4999759b571109e9cf`.
- Fixed head: `e055adb0148d8be69b42e2874a38641efa512e04`.
- Head tree: `3eafab29e06b9f901c07070bace13f2b1d86f538`.
- Range: one commit, `e055adb test: qualify execution context across public consumers`.
- Review target state: clean. `git status --porcelain=v1`, unstaged diff, and staged diff were empty; `git diff --check 04c66e84e4b05814e462ba4999759b571109e9cf...e055adb0148d8be69b42e2874a38641efa512e04` passed.
- Requested reviewer settings: `gpt-5.6-sol` / `high`; effective runtime model and effort are not exposed and therefore unverified.

## Result

**CLEAN — 0 actionable Standards findings. Worst severity: none.**

The five-file diff is coherent with the repository rules and applicable ADRs:

- The two new `NotesDemoIT` compositions use only existing public Kernel seams and the production `NoteApplicationService` plus real PostgreSQL persistence. They do not add a test-only production entry point, transport DTO, Service API, Adapter, or dependency.
- Direct submission captures the complete caller object at submission, covers two distinct same-tenant identities, another tenant, cross-tenant rejection, Platform rejection, and explicit absence. Every success and failure is followed by a FIFO probe on the same single worker, demonstrating restoration of its exact prior Platform context.
- Callback registration captures two distinct request identities, later completes each source on the external single worker, proves that the business read ran on that completion thread, and probes exact restoration after each completion.
- The checks assert the whole propagated context by object identity, exercise the Application authorization and tenant repository guards, and bound failures with two-second Future timeouts. Setup and cleanup are local to the test; post-cleanup Note, Outbox, and receipt counts prove no retained business/message side effect from read or rejected branches.
- The new guide's JDK executor, callback, Reactor, and Spring AI snippets match the current public method signatures. It distinguishes fixed request-owned captures from shareable identity-free adapters, describes actual-exit cleanup, and explicitly excludes global Hooks, raw/common-pool propagation, arbitrary SDK/Advisor callbacks, complete MVC async-return propagation, Platform messages, nullable tenant messages, and unrelated ThreadLocal/transaction state.
- ADR 0037, the Notes README, and Product Surface promote only the public consumers and combinations supported by current source evidence. Claims stay bounded to a test-source scripted AI model and plaintext Kafka runtime behavior, and preserve producer-authentication and destination-ACL limits. The Product Surface keeps WebFlux request entry and complete MVC async-return integration under Planned.
- Dependency evidence binds the Kernel to `java.base`, the Spring-task consumer to Kernel plus Spring Core/Context 7.0.9 without Reactor/AI, the Reactor consumer to Kernel plus Reactor Core 3.8.7 without AI, and the AI consumer to Spring AI 2.0.1/Reactor 3.8.7/Spring 7.0.9. No production Java or POM/BOM file changed in this ticket.

## Evidence reviewed

- Repository `AGENTS.md`, project `code-review` Skill, delivery standards, reviewer execution profile, the full ticket, parent spec, ticket map, worker report, and current Product Surface.
- ADR 0003, 0004, 0018, 0021, 0031, 0034, 0035, and the full ADR 0037.
- Complete fixed-point diff and current source for `NotesDemoIT`, `NoteApplicationService`, `MybatisPlusNoteRepository`, `ContextExecutors`, `ExecutionContextSnapshot`, `ExecutionContextHolder`, `ReactorExecutionContexts`, and `SpringAiExecutionContexts`.
- Existing C12 log: affected common-baseline verification reported 237 tests, zero failures/errors/skips, and `BUILD SUCCESS`. C13 dependency-tree logs and the worker report's JAR/tree identities were inspected. These were not rerun during this read-only review.
- Completed C15 record and both topology logs: the microservices run performed the build (`skip_build: 0`) and passed the public contract plus Kafka stop/recovery; the business-core-monolith run reused that artifact set (`skip_build: 1`) and passed the same contract and recovery phases. No command was rerun during this terminal-state check.

## Scenario verification and formal limit

- C15 is **PASS and verified by this reviewer at the unchanged worker head**. `scenario-evidence.json` records exact head `e055adb0148d8be69b42e2874a38641efa512e04` and tree `3eafab29e06b9f901c07070bace13f2b1d86f538` before and after both runs, with overall `status: PASS`. Microservices exited 0 in 316.92 seconds; business-core-monolith exited 0 in 124.164 seconds. Both logs end with public-contract and Kafka-recovery PASS. The record binds the shared build to SHA-256 identities and sizes for all six App JARs. The worktree is still clean at the same head/tree after the runs.
- This terminal C15 evidence creates no new Standards finding. The source result remains **CLEAN — 0 actionable findings**.
- The repository Normal quality gate and final acceptance remain coordinator-owned and were not run here.
- This report verifies the ticket-11 Standards axis and exact-worker-head Scenario evidence only; it does not claim the formal whole-feature final acceptance result.
