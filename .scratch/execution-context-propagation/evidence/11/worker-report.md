## Current coordinator qualification status

Ticket-level C15 is now PASS at committed worker head `e055adb0148d8be69b42e2874a38641efa512e04`: microservices exit0/316.92s, business-core-monolith exit0/124.164s, public contracts and Kafka recovery passed with unchanged source/tree and six shared JAR digest bindings. See `qualification-scenario.md` and external `scenario-e055adb/scenario-evidence.json`. The later integrated full tree is identical. This supersedes the historical worker-handoff C15-pending statements below; it does not replace final delivery-head gate/Scenario evidence.

# Ticket 11 worker report

## Fixed point and ownership

- Ticket: `.scratch/execution-context-propagation/issues/11-composition-and-consumer-qualification.md`
- Worktree: `/private/tmp/execution-context-frontier-20260905/11`
- Branch: `codex/execution-context-20260905-11`
- Full base: `04c66e84e4b05814e462ba4999759b571109e9cf`
- Worker head: `e055adb0148d8be69b42e2874a38641efa512e04` (`test: qualify execution context across public consumers`), created by the coordinator from the exact staged index. The worktree is clean.
- Direct prerequisites `8a61f7cafa9dc2c509a18818d9b8f867fb3e6d8e` (04), `1893acfe2aec90cc833285e68a559318f2e2045a` (05), `28d0644cec7dd263843113386fe06f560b502055` (06), `8d7a7f3b8ac6aa6402f747a4e49673183d47b231` (07), `c3ac8c7a2444f51d75c8f9add995e810e3921b9e` (09), and `1f02a5d4ede11fc6ad6dc604cf0c1dd369a68190` (10), plus all transitive predecessors, were verified as ancestors before implementation.
- Root owns the ordinary commit, integration, independent dual-axis review, tracker writes, Normal gate, final exact-head Scenario, and final acceptance. This worker did not change the ticket, parent spec, original finding, tracker, production Java, POM/BOM, or reference-product harness.
- Exact committed patch: five files, 403 insertions and 11 deletions; SHA-256 of both the staged binary diff and `git diff --binary 04c66e84...e055adb0` is `1e22a17300dc918e852f0b33be060b362ef8514e945142b58c2781fb130800fe`.
- `git diff --cached --check` before commit and `git diff --check 04c66e84...e055adb0` after commit: PASS. Filesystem validation checked every local link in the four changed Markdown files: PASS. The repository-owned commit/blob check `python3 tools/quality/quality_gate.py _check markdown-links --repo . --base 04c66e84e4b05814e462ba4999759b571109e9cf --head e055adb0148d8be69b42e2874a38641efa512e04` also passed for all four changed Markdown files.

## Delivered behavior

The independent Notes consumer now exercises two public combinations through the production `NoteApplicationService` and real PostgreSQL persistence Adapter:

1. an explicitly trusted complete Holder context submits work through `ContextExecutors.propagating(...)`; the task reads a Note through the Application Service;
2. an explicitly trusted complete Holder context captures and binds a `Function` when a `CompletableFuture` callback is registered; a later external worker completes the source Future, and the callback reads the Note on that exact completion thread.

The direct-task composition covers Tenant A with two distinct complete Actor/Initiator/Correlation identities, Tenant B, cross-tenant rejection, Platform rejection, and explicit absence. Each success and failure is followed by a probe on the same single worker proving restoration of the exact pre-existing foreign Platform context object. The callback composition covers two distinct identities in one tenant, proves callback execution on the external completion thread, and proves exact restoration after each completion. Both tests compare Note, Outbox, and receipt counts before setup and after cleanup; the read and rejected branches leave all three unchanged.

The new adoption guide separates direct-submit capture, callback-registration binding, and native framework carriers. It states fixed-request ownership, shareable stateless adapters, actual-exit cleanup, absent/cancel/timeout/retry semantics, tenant-resource failure rules, and unsupported models. ADR 0037 and Product Surface only claim consumers that were run at this common source baseline. There is no HTTP-to-async claim, complete MVC async-return claim, external provider claim, global Hook, Platform message, producer-authentication claim, or destination-ACL claim.

## Current command registry

The mapping tables below refer to these exact commands and results. Historical predecessor commands and logs are fixed by each listed ticket worker report under `/private/tmp/execution-context-frontier-20260905/evidence/<ticket>/worker-report.md`; C11-C14 were run in this ticket worktree at source base `04c66e84...` plus the ticket-11 patch now committed unchanged as `e055adb0...`. C15 is pending.

- **C01 — Kernel recovery/model:** `./mvnw -pl framework/foundation/moduvera-kernel -am verify`. Ticket 01 final: PASS, 30 tests; ticket 04 final after executor closure: PASS, 52 tests, Spotless, PMD and JaCoCo. `jdeps --print-module-deps framework/foundation/moduvera-kernel/target/moduvera-kernel-0.1.0-SNAPSHOT.jar`: `java.base`.
- **C02 — resource and compatibility guards:** `./mvnw -pl services/order/order-service -am -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MySqlBusinessRepositoriesIT -Dfailsafe.failIfNoSpecifiedTests=false verify`: ticket 02 PASS, 5 real MySQL tests. `./mvnw -pl examples/simple-notes-demo -am -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=NotesDemoIT -Dfailsafe.failIfNoSpecifiedTests=false verify`: ticket 02 PASS, 2 real PostgreSQL consumer tests.
- **C03 — registered callbacks:** `./mvnw -pl examples/simple-notes-demo -am -Dtest=RequestBoundCallbackConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`: ticket 03 final PASS, 2 tests through the 11-module consumer reactor. `./mvnw -pl framework/foundation/moduvera-kernel -am verify`: ticket 03 PASS, 43 tests plus Enforcer, Spotless, PMD and JaCoCo.
- **C04 — JDK executors:** `./mvnw -pl framework/foundation/moduvera-kernel -am verify`: ticket 04 review closure PASS, 52 tests. `./mvnw -pl examples/simple-notes-demo -am -Dtest=JdkExecutorContextConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS, 1 public-consumer test. Its removal-of-bulk-binding mutation produced the expected RED before restoration.
- **C05 — Spring task:** `./mvnw -l /private/tmp/execution-context-frontier-20260905/evidence/05/affected-verify.log -pl framework/bom,framework/adapters/moduvera-spring-task-context,verification/moduvera-spring-task-context-consumer -am verify`: PASS, 62 tests total (Kernel 52, adapter 4, independent consumer 6), zero failures/errors/skips, with Spotless, PMD and JaCoCo.
- **C06 — HTTP entry:** `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-auth-final-v2.log -pl framework/starters/moduvera-auth-resource-server-autoconfigure -am verify -Dit.test=HttpExecutionBoundaryIT -Dfailsafe.failIfNoSpecifiedTests=false`: ticket 06 final review closure PASS, 18 auth unit tests and 5 embedded-Tomcat ITs. `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-catalog-final-rerun.log -pl apps/catalog-app -am verify -Dit.test=CatalogApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`: PASS, 8 virtual-thread Catalog ITs. Ticket 06 also ran `verification/reference-product/harness/verify.sh all`: both then-current topologies PASS; this historical result is not substituted for the required ticket-11 final fixed-head Scenario.
- **C07 — Reactor:** `./mvnw -pl verification/moduvera-reactor-context-consumer -am verify`: ticket 07 PASS, Kernel 43, adapter 8, independent consumer 2, plus convergence, Spotless, PMD and JaCoCo. `./mvnw -pl verification/moduvera-reactor-context-consumer -am dependency:tree -Dverbose`: Reactor Core 3.8.7; no Spring AI.
- **C08 — AI request/response:** `./mvnw -pl framework/adapters/moduvera-spring-ai-context,verification/moduvera-spring-ai-context-consumer -am verify`: ticket 08 PASS, Kernel 37, adapter 4, independent consumer 5; true streaming response and concurrent complete identities. Dependency trees resolved Spring AI 2.0.1, Reactor 3.8.7 and Spring Framework 7.0.9; the non-AI BOM consumer contains no Spring AI.
- **C09 — AI tool loop:** the same AI affected-verify command after ticket 09: PASS, Kernel 43, adapter 6, independent consumer 8 including 3 real `ChatClient`/`ToolCallingAdvisor` loop tests, 57 total. Four concurrent requests cover Tenant A two identities, Tenant B and Platform, with two tool rounds, final stream response, and same-thread restoration.
- **C10 — message compatibility:** `./mvnw -pl apps/inventory-app -am -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=InventoryApplicationIT verify`: ticket 10 PASS, 8 real PostgreSQL/Kafka ITs including the same-partition negative completion barrier. `./mvnw -pl apps/order-app -am -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=OrderApplicationIT verify`: PASS, 7 PostgreSQL/Kafka ITs including transaction rollback. `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=OutboxRelayIT verify`: PASS, 6 real PostgreSQL tests. `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=JdbcMessagingMySqlIT verify`: PASS, 8 real MySQL tests.
- **C11 — new Notes compositions:** `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/11/02-notes-composition-focused-escalated.log -pl examples/simple-notes-demo -am -Dit.test=NotesDemoIT#trustedEntriesPropagateDirectTasksIntoTenantBusinessReadsAndRestoreTheWorker+registeredCallbacksReadBusinessStateOnTheExternalCompletionThreadAndRestoreIt -Dfailsafe.failIfNoSpecifiedTests=false verify`: PASS, 2/2 Notes composition ITs, 0 failures/errors/skips, 12.087 s. The first sandboxed attempt could not see Docker and is retained as `01-notes-composition-focused.log`; no test assertion failed.
- **C12 — common-baseline affected verification:** `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/11/03-all-context-consumers-affected-verify.log -pl examples/simple-notes-demo,verification/moduvera-reactor-context-consumer,verification/moduvera-spring-task-context-consumer,verification/moduvera-spring-ai-context-consumer -am verify`: PASS, 237 tests total, zero failures/errors/skips, 1:23. Direct result totals include NotesDemoIT 4, Reactor consumer 2, Spring task consumer 6, AI consumer 8 including 3 tool-loop cases; relevant adapters and dependency modules also passed. Real infrastructure included PostgreSQL 18.6, MySQL 8.4.11, Kafka 7.3.3 and Testcontainers 2.0.5.
- **C13 — dependency closures:** `./mvnw --log-file .../04-kernel-runtime-dependency-tree.log -pl framework/foundation/moduvera-kernel -am dependency:tree -Dscope=runtime`; equivalent commands for the Spring-task, Reactor and AI independent consumers are logged as `05` through `07`. All PASS. Kernel has no runtime dependency; Spring task resolves Kernel plus Spring Core/Context 7.0.9 and no Reactor/AI; Reactor resolves Kernel plus Reactor Core 3.8.7 and no AI; AI resolves Kernel plus Spring AI 2.0.1, Reactor 3.8.7 and Spring 7.0.9.
- **C14 — source hygiene:** `git diff --check` before staging and `git diff --cached --check` after staging: PASS. Manual current-filesystem validation: all local links in the four changed Markdown files exist; no trailing whitespace found. C12 is the post-Clean-Code verification; no source changed after it.
- **C15 — final combined Scenario:** `verification/reference-product/harness/verify.sh` at the coordinator's final committed integration head: **PENDING / NOT RUN by this worker**, as explicitly assigned to the coordinator. It must not be inferred from ticket 06's older run or C12.

## US01-US22 mapping

| Story | Actual owner and consumer | Current evidence |
|---|---|---|
| US01 | 01/02 Kernel model and Holder; Notes/MySQL resource consumers | C01, C02, C12 PASS |
| US02 | 02 Platform/Tenant resource guards; Notes and MySQL production Adapters | C02, C11, C12 PASS |
| US03 | 01/03 Snapshot/Bound callbacks; Notes callback consumer | C01, C03, C11, C12 PASS |
| US04 | 01/03/04 lifecycle and executor behavior; Notes direct-task consumer | C01, C03, C04, C11, C12 PASS |
| US05 | 01/02 safe nested/restorable complete context | C01, C02, C11, C12 PASS |
| US06 | 01/03 strict/allow-absent/bound use; Notes consumer | C01, C03, C04, C11, C12 PASS |
| US07 | 01/03 callback value/exception compatibility | C01, C03, C12 PASS |
| US08 | 04 JDK executor and 05 Spring TaskExecutor; Notes and Spring-task consumers | C04, C05, C11, C12 PASS |
| US09 | 03/04 registration versus submission capture | C03, C04, C11, C12 PASS |
| US10 | 03/04 actual execution lifetime and restoration | C03, C04, C11, C12 PASS |
| US11 | 03/04/05 callback/executor/Async lifecycle and identities | C03, C04, C05, C11, C12 PASS |
| US12 | 06 managed HTTP selector and Platform/Tenant entry | C06 plus C12 affected auth suites PASS; C15 pending |
| US13 | 06 signed authentication mapping and status behavior | C06 plus C12 affected auth suites PASS; C15 pending |
| US14 | 02/06 tenant resource and dispatch cleanup | C02, C06, C12 PASS; C15 pending |
| US15 | 07 native Reactor context and real scheduler | C07, C12 PASS |
| US16 | 07 strict native reads, retry/cancel/restoration and no Hook | C07, C12 PASS |
| US17 | 08 AI request injection and final streaming response | C08, C09, C12 PASS |
| US18 | 09 real two-round tool loop | C09, C12 PASS |
| US19 | 08/09 retained response context and shared stateless mappers | C08, C09, C12 PASS |
| US20 | 02 Job/Lock/resource compatibility and 10 tenant-only message compatibility | C02, C10, C12 PASS; C15 pending |
| US21 | 02-10 public/compatibility consumers and ticket-11 closure audit | C01-C13 PASS; exact artifacts below; C15 pending |
| US22 | per-ticket lifecycle docs plus ticket-11 cross-boundary guide/Product Surface | C03-C14 PASS; final exact-head gate/Scenario pending |

No row marked PASS uses a planned type, POM coordinate, or prior ticket result as the ticket-11 common-baseline composition result. C12 provides the current common source-state run for Notes, Spring task, Reactor, and AI; C06/C10 remain scenario-specific predecessor runtime evidence and C15 is disclosed as pending.

## T01-T09 mapping

| Test seam | Actual owner and observable consumer | Current evidence |
|---|---|---|
| T01 | 01-03 Holder/Snapshot/Bound tests and Notes callback consumer | C01, C03, C11, C12 PASS |
| T02 | 04 JDK and 05 Spring real executors/proxies | C04, C05, C11, C12 PASS |
| T03 | 03/04 real Future/SDK-shaped callbacks and executor combinations | C03, C04, C11, C12 PASS |
| T04 | 06 signed-JWT embedded Servlet and Catalog virtual-thread consumers | C06 and C12 PASS; final topology C15 pending |
| T05 | 07 Reactor native Context across real scheduler | C07, C12 PASS |
| T06 | 08/09 real ChatClient, two tool rounds and final stream | C08, C09, C12 PASS |
| T07 | 02/10 production database/message Adapters; Notes composition read/rejection side-effect counts | C02, C10, C11, C12 PASS; final topology C15 pending |
| T08 | 02-10 compatibility/dependency checks; ticket-11 resolved closures and artifact binding | C01-C14 PASS; exact closure and artifact identities below |
| T09 | Notes, Spring task, Reactor, AI, HTTP/message reference consumers and adoption guide | C03-C14 PASS; final topology C15 pending |

## Exact dependency, source and artifact identity

Environment: Apache Maven 3.9.16; Oracle OpenJDK 26 (`26+35-2893`); macOS 26.5.2 aarch64. The repository baseline resolves Spring Boot 4.1.1, Spring Framework 7.0.9, Reactor Core 3.8.7 and Spring AI 2.0.1. C13 contains the actual runtime dependency trees; `jdeps` on the built Kernel returned `java.base`.

The C12 build produced these exact JAR SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| `moduvera-kernel-0.1.0-SNAPSHOT.jar` | `05ee34403a91140253785eaa049b3f1f55d6f1e138c13806e8b9bb43b1e9ed52` |
| `moduvera-spring-task-context-0.1.0-SNAPSHOT.jar` | `b41756afc3839333986eaac20872f31a27e2d607a4b9823bd8658c4de9d78fde` |
| `moduvera-reactor-context-0.1.0-SNAPSHOT.jar` | `080382dbb08597d468fd0a2b6c5a98b9b2612b7705fa579e8b9013c07c8c8a03` |
| `moduvera-spring-ai-context-0.1.0-SNAPSHOT.jar` | `b23b740647308aaf243f7c044ab44dd6bf893d7b56afc95d951c4f122c618d67` |
| `simple-notes-demo-0.1.0-SNAPSHOT.jar` | `538a5175a4cb6310f8ef07c3e77cfd7c05377bf4cdbd5678eceef5118a300238` |
| Spring-task consumer JAR | `6b7f2ecdfbba82c8c6c055cd0dc3e0bf83a30e07001e5f4520d71cf0457929cf` |
| Reactor consumer JAR | `21d3f2c919ea93b24451c7b0093550c7606ba00ee84ad0f4525cb16e66666fa4` |
| AI consumer JAR | `c2e0817fb556926d6be8a31943c834f8e77f7420eddd68e5b1dc88a0b1315775` |

The unchanged production/consumer module source trees at `04c66e84...` are respectively: Kernel `e4a4a098b99a661bdddd4affe218064af98686db`; Spring task adapter `26e906e4b8e9f8926680c982e58ac941a2cfd0cf`; Reactor adapter `781acd3ee658cc408efd368d23d0d3b89a35a83c`; AI adapter `cf89ddfc5e3010088d997204fe02bddd4587188f`; Spring-task consumer `b78724cffd63c01a25940b66a950bd42bed143be`; Reactor consumer `07f5dc73840a229a1b80ebdcaa407755b2eff314`; AI consumer `ea6ea52c5cff9aecdc7316c37ef179cd756d5db3`. The base Notes module tree is `104d6ff6e387ee71651a9c9a241301553b390eac`; its staged `NotesDemoIT.java` blob is `5ac6f67790ab6a95d6651bbfb63e4af26f8b64e0`. No production Java changed in ticket 11, so the JARs bind to the listed committed production trees while the new composition test binds to that staged blob and C12 log.

JaCoCo XML reports were present after C12 for Notes and all three independent consumers. Their SHA-256 values are Notes `8017acdd7131b48e55644a7e973548217fbc13c9b68105a6d0af978b4d69e9a9`, Spring task `4a3cde64b11ecae7f0ea7342a98fbe12c4a9793d824e57abc78e0b6bdf70e009`, Reactor `7d681a5927e359988b0c075272f7c0b984edcf852b780e7bde714a59a18e8c9f`, and AI `796563e696a6e84752edfa5806cdc84c963125862e58e5aad0cf4c10c9de7045`.

## Changed files

- `examples/simple-notes-demo/src/test/java/io/github/ande1922/moduvera/example/notes/NotesDemoIT.java`: two real PostgreSQL Application-Service compositions and bounded lifecycle/side-effect helpers.
- `docs/implementation/EXECUTION-CONTEXT-PROPAGATION.md`: unified adoption, lifecycle, evidence and unsupported-boundary guide.
- `docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md`: supports only the consumer-qualified Kernel/JDK callback, selected Spring task, Reactor, and Spring AI surfaces; narrows the remaining WebFlux/MVC planned item and preserves broker trust limits.
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`: only the allocated ticket-11 composition/qualification section.
- `examples/simple-notes-demo/README.md`: exposes the two public composition examples and their bounded evidence.

## Limitations and remaining formal steps

- C15 has not run. The coordinator must commit/integrate this exact patch, bind all final evidence to that committed head, run both supported reference-product topologies, and report their actual outcome. Ticket 06's earlier dual-topology PASS is historical regression evidence only.
- Independent Standards and Spec review, finding closure, repository Normal quality gate, final acceptance, tracker evidence/status changes, and parent spec/finding lifecycle remain coordinator-owned. This ticket is not the formal final PASS.
- The focused first attempt failed only because sandboxed Docker discovery was unavailable; the authorized identical rerun and C12 both passed with live Testcontainers.
- Plaintext Kafka Testcontainers proves the documented message contract/runtime behavior, not broker producer authentication or destination ACL enforcement. These are deployment prerequisites.
- The AI consumer uses a test-source scripted model and makes no paid or external provider claim. Arbitrary SDK/Advisor callbacks remain explicit boundaries.
- The Notes compositions start from an explicitly trusted Holder context. They do not prove an unimplemented JWT/Servlet-to-async chain or complete MVC async-return propagation.
- The coordinator created the ordinary worker commit `e055adb0148d8be69b42e2874a38641efa512e04` from the exact staged index. This worker performed no integration, tracker update, push, publish, deploy, worktree cleanup, paid call, or external side effect.

## Coordinator fixed-commit update

The coordinator committed the original five-file staged patch without edits as `e055adb0148d8be69b42e2874a38641efa512e04`. Its full base-to-head binary diff matches staged SHA-256 `1e22a17300dc918e852f0b33be060b362ef8514e945142b58c2781fb130800fe`; the worktree is clean. Staging/source-HEAD statements above describe the historical worker handoff. Fixed worker-head Scenario is running under `scenario-e055adb/`; it is not yet passing evidence.
