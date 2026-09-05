# Ticket 02 Spec review

- Axis: Spec
- Fixed base: `4b7baf1904ae34bb63525939b8afef60bc74c8a1`
- Reviewed head: `dbb2a1ade64f1d6bb8ddb31cf52822f5f6e453e7`
- Range: valid, one commit (`dbb2a1a feat: add platform execution scope guards`)
- Checkout: `codex/execution-context-20260905-02`, clean at review start
- Result: **clean — 0 actionable Spec findings**

## Acceptance assessment

The immutable `ExecutionContext` now carries explicit Platform or validated Tenant scope while retaining Actor, Initiator, and correlation validation. `Holder.require()` continues to distinguish presence from absence; `requireTenantId()` and the legacy `tenantId()` alias reject Platform. Kernel evidence covers the three Holder states, Platform/Tenant restoration, different-tenant and same-tenant/different-request identity restoration, nested absence, and normal and exceptional cleanup.

All affected production tenant persistence adapters perform a strict Tenant read before mapper work. The MySQL and independent PostgreSQL tests use production adapters and real Testcontainers databases, reject Platform and absence for reads and writes, assert rejected writes leave row counts unchanged, and retain Tenant A/Tenant B isolation. The direct Catalog and Identity HTTP consumers fail before token or transport invocation. Job tests preserve the supplied full context independently of GLOBAL lock competition and reject Platform for TENANT lock keys without expanding the Job/Lock API.

The legacy Tenant constructor, `initiatedBy`, `tenantId`, Holder `run`/`call`, and Snapshot Runnable/Callable wrappers remain callable. The change correctly documents the intentional record-component, reflection, record-pattern, generated `toString`, and record-derived serialization shape break. Repository scanning found no production reflection or serialization consumer. The preserved legacy consumer bytecode invokes the former descriptors and passes when run with the reviewed head's Kernel classes. Kernel dependency evidence remains framework-free (`jdeps`: `java.base`).

`CONTEXT.md`, ADR 0003, ADR 0035, and ADR 0037 consistently describe absence as distinct from Platform, Platform as neither authorization nor cross-tenant access, ordinary tenant resources as fail-closed, GLOBAL as lock competition only, ordinary business APIs as tenant-transparent, and cross-tenant/IAM concerns as separately authorized and excluded. The range contains no HTTP authentication entry, AI/Reactor, message-wire, scheduler/lock product-surface, tracker-state, or unrelated matrix expansion.

## Evidence inspected

- `git status --short --branch`; `git rev-parse HEAD`; ancestry checks for the fixed base and integrated ticket 01; `git log`/`git diff --name-status`/`git diff --check` for the immutable range.
- Full ticket, approved spec/map, `AGENTS.md`, `CONTEXT.md`, ADR 0003/0018/0034/0035/0037, repository delivery standards, code-review skill, and worker report.
- Source and tests for Kernel model/Holder/Snapshot, MyBatis guard, Catalog/Inventory/Order/Notes persistence, Catalog/Identity direct HTTP clients, and JobRunner/Lock behavior.
- Worker logs: Kernel 37 tests; MyBatis guard 3; Job 4; direct HTTP 10; MySQL 5 with MySQL 8.4.11 Testcontainer; affected 16-module unit reactor; independent Notes 2 with PostgreSQL 18.6 Testcontainer; all relevant summaries report 0 failures, errors, and skips and `BUILD SUCCESS`.
- Compatibility/dependency artifacts: legacy consumer source and bytecode, current descriptors, repository reflection/serialization scan, Maven dependency tree, and `jdeps` output.
- Focused independent compatibility check: `java -cp /private/tmp/execution-context-frontier-20260905/evidence/02/binary-compat/consumer-classes:framework/foundation/moduvera-kernel/target/classes LegacyTenantConsumer` -> `PASS legacy tenant constructor/accessor/initiatedBy/run/call/wrap`.

The earlier Docker sandbox denial, the corrected Product equality assertion, the PostgreSQL `Instant` fixture correction, the explicitly reported overwritten intermediate PostgreSQL log, and the earlier loopback-bind failure are superseded by the final green evidence and do not indicate a failure at the reviewed head.
