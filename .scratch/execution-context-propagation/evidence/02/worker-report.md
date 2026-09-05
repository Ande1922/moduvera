# Ticket 02 worker report

- Branch: `codex/execution-context-20260905-02`
- Commit: `dbb2a1ade64f1d6bb8ddb31cf52822f5f6e453e7`
- Fixed base: `4b7baf1904ae34bb63525939b8afef60bc74c8a1`
- Prerequisite 01: `0226e17cfbc48fdd2af42a2b132f438f5eb94177` (ancestor of commit)
- Worktree: clean after commit

## Acceptance mapping

1. `ExecutionScope` is a sealed immutable Platform/Tenant model; Tenant holds a validated non-null `TenantId`. `ExecutionContext` retains Actor, Initiator and correlation validation.
2. Holder absence remains distinct. `require()` accepts either present scope; `requireTenantId()` and legacy `tenantId()` reject Platform strictly.
3. Kernel tests cover Platform -> Tenant -> same-tenant/different Actor, Initiator and correlation -> absent restoration, existing different-tenant nesting, and success/exception cleanup.
4. The old tenant constructor, `initiatedBy(TenantId, ...)`, `tenantId()`, Holder `run`/`call`, and Snapshot Runnable/Callable wrappers remain. A class compiled against the base API runs against the new classes. The first record component intentionally changes to `scope`; reflection, record-pattern source, generated `toString`, and record-derived serializers see the new structure. `ExecutionContext` is not Java-serializable or a network DTO, and the repository scan found no production reflection/serialization consumer.
5. Production repositories and the MyBatis tenant handler require Tenant scope. Real MySQL and PostgreSQL tests prove Platform and absence reject reads and writes, rejected rows remain absent/row counts unchanged, and Tenant A/Tenant B isolation remains.
6. Catalog and identity outbound tests prove Platform fails before token acquisition or an HTTP request. Existing tenant-only message consumers retain the strict legacy alias; message wire contracts were not changed.
7. Job tests prove GLOBAL is only lock competition while preserving the supplied Tenant or Platform context, and a Platform context cannot create a TENANT lock key. Holder cleanup remains exact.
8. `CONTEXT.md`, ADR 0003, ADR 0035, and existing propagation ADR 0037 document the scope, fail-closed resource rules, compatibility boundary, independent authorization, and exclusions.

## Verification

- `./mvnw -pl framework/foundation/moduvera-kernel -am test`: baseline 30 tests, then final three-state 37 tests; all pass, no skips (`01-kernel-baseline.log`, `02-kernel-three-state.log`).
- `./mvnw -pl framework/starters/moduvera-data-mybatis-plus-spring-boot-starter -am -Dtest=ExecutionContextTenantLineHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`: 3 pass (`03-mybatis-guard-unit.log`).
- `./mvnw -pl framework/starters/moduvera-scheduler-spring-boot-starter -am -Dtest=JobRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`: 4 pass (`04-job-lock.log`); the final affected reactor repeats the cleaned-up test.
- `./mvnw -pl services/order/order-service -am -Dtest=CatalogHttpClientTest,IdentityServiceTokenProviderTest -Dsurefire.failIfNoSpecifiedTests=false test`: 10 pass (`05-tenant-http.log`).
- `./mvnw -pl services/order/order-service -am -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MySqlBusinessRepositoriesIT -Dfailsafe.failIfNoSpecifiedTests=false verify`: 5 pass, 0 skipped, real MySQL Testcontainer (`08-mysql-resource-guards-green.log`).
- `./mvnw -pl examples/simple-notes-demo -am -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=NotesDemoIT -Dfailsafe.failIfNoSpecifiedTests=false verify`: 2 pass, 0 skipped, independent Boot-parent consumer with real PostgreSQL and its existing Kafka behavior (`18-postgresql-independent-consumer-final.log`).
- `./mvnw -pl framework/foundation/moduvera-kernel,framework/starters/moduvera-data-mybatis-plus-spring-boot-starter,framework/starters/moduvera-scheduler-spring-boot-starter,services/catalog/catalog-service,services/inventory/inventory-service,services/order/order-service -am test`: all 16 reactor modules succeed; relevant totals include Kernel 37, MyBatis starter 6, Scheduler 5, Catalog 3, Inventory 18, Order 44, all with zero failures/errors/skips (`17-affected-unit-reactor-green.log`).
- Focused `spotless:apply` for the six Parent-managed changed modules: success, no source changes required after cleanup.
- Base-compiled consumer: `git archive` base kernel sources, `javac` old API, compile `LegacyTenantConsumer` against old classes, run it with only new kernel classes: PASS (`11-binary-compatibility.log`).
- `./mvnw -pl framework/foundation/moduvera-kernel dependency:tree`: no compile/runtime dependencies (`12-kernel-dependency-tree.log`); `jdeps --print-module-deps`: `java.base` (`13-kernel-jdeps.log`).
- `javap -p -s ExecutionContext`: new scope canonical descriptor plus preserved old tenant constructor/accessor descriptors (`14-execution-context-descriptors.log`).
- Targeted source scan for ExecutionContext reflection/serialization found only the new record-component assertion (`15-record-serialization-scan.log`).
- `git diff --cached --check`: pass before commit.

## Iteration classification

- `06-mysql-resource-guards.log`: environment-only failure; sandbox denied Docker socket access before useful database evidence.
- `07-mysql-resource-guards-escalated.log`: test expectation defect; `Product` has identity equality, so `Optional.contains(existing)` was invalid. Field assertions replaced it; no production change resulted.
- `09-postgresql-independent-consumer.log`: test-fixture defect; PostgreSQL JDBC could not infer a SQL type for raw `Instant`. The seed binds `Timestamp` now.
- A subsequent PostgreSQL iteration (its log was overwritten by the later green run) showed the Platform read rejection arose inside MyBatis and was wrapped. This exposed a production guard-placement gap: Notes `findById` checked only presence. It now calls `requireTenantId()` before mapper invocation, producing the strict boundary failure before SQL.
- `16-affected-unit-reactor.log`: environment-only failure; four existing transport tests could not bind loopback sockets in the sandbox. The identical escalated command is green in `17-affected-unit-reactor-green.log`.

## Limits

- No whole-repository `clean verify` was run; verification is scoped to all changed modules plus the two required real-database consumers.
- No HTTP authentication entry behavior, message wire format, scheduler/lock product API, tracker state, or future propagation adapter was changed.
- The record structural change is intentionally not source/reflection/derived-serialization compatible; ordinary precompiled tenant calls are binary-compatible as demonstrated.
