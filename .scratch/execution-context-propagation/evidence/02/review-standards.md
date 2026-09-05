# Ticket 02 Standards review

## Fixed point and axis

- Axis: Standards — correctness/security, repository architecture and rules, test quality, and compatibility; completed independently and read-only.
- Worktree: `/private/tmp/execution-context-frontier-20260905/02`
- Branch: `codex/execution-context-20260905-02`
- Base: `4b7baf1904ae34bb63525939b8afef60bc74c8a1`
- Head: `dbb2a1ade64f1d6bb8ddb31cf52822f5f6e453e7`
- Range: one commit, `dbb2a1a feat: add platform execution scope guards`; `git merge-base` equals the supplied base.
- Checkout state: clean before and after review.
- Conclusion: clean, 0 actionable Standards findings. Worst severity: none.

## Standards assessment

The execution model remains an immutable, framework-free Kernel value. `ExecutionScope` is a sealed Platform/Tenant model, its Tenant variant requires an already-validated non-null `TenantId`, and `ExecutionContext` still validates the Actor, Initiator, and correlation fields. Absence remains a Holder state rather than a Platform value. The legacy tenant constructor, `initiatedBy(TenantId, ...)`, and `tenantId()` descriptor remain available, while Platform fails through `requireTenantId()` instead of producing null or a magic tenant.

The fail-closed placement is before the protected side effect. The changed Catalog, Order, Inventory, and Notes repositories resolve `requireTenantId()` before their first mapper call. `CatalogHttpClient` resolves it before token acquisition and transport invocation, and `IdentityServiceTokenProvider` resolves it before its cache or identity transport. `JobRunner` derives a Tenant lock key only from a Tenant context, while a GLOBAL lock key leaves the supplied Platform or Tenant context unchanged. These placements conform to ADR 0003, ADR 0035, ADR 0037, and the tenant-transparent repository boundary.

The runtime evidence is proportionate rather than an implicit Cartesian support-matrix expansion. The MySQL integration test exercises a production Catalog repository for Platform and absent reads/writes, verifies the rejected row count and ID remain unchanged, then rechecks Tenant A access and Tenant B isolation. The independent Notes consumer exercises a production MyBatis-Plus repository on PostgreSQL with the same negative read/write and unchanged-state assertions. In both cases source inspection confirms the guard precedes mapper invocation, so the observed exception does not rely only on a post-query assertion. The tests do not claim every persistence adapter/database pairing.

Whole-context restoration is checked by identity, not only by Tenant ID. `ExecutionContextHolderTest` nests Platform, Tenant A, same-Tenant/different Actor-Initiator-Correlation, and explicit absence, asserting the exact context instance at each restoration point; it also covers exceptional Tenant execution restoring Platform. Existing Tenant-to-Tenant, same-object nesting, invalid close-order, cross-thread closure, and Snapshot lifecycle checks remain green. `JobRunnerTest` likewise asserts the exact supplied Tenant or Platform context and confirms GLOBAL lock competition does not alter scope.

Compatibility evidence matches the documented limit. A consumer compiled against the base Kernel runs with only the new Kernel classes and exercises the legacy constructor/accessor/factories/Holder/Snapshot calls. `javap` confirms the preserved legacy descriptors and the intentional new canonical `scope` component; the repository scan found no production reflection or serialization consumer. `jdeps --print-module-deps` reports only `java.base`, consistent with ADR 0018. The structural record-component/source/derived-serialization change is explicitly documented and is not presented as complete compatibility.

## Evidence reviewed

- `01-kernel-baseline.log`: baseline Kernel suite, 30 tests.
- `02-kernel-three-state.log`: final Kernel suite, 37 tests, 0 failures/errors/skips, build success.
- `03-mybatis-guard-unit.log`: MyBatis guard unit tests, 3 passing.
- `04-job-lock.log`: Job/Lock tests, 4 passing.
- `05-tenant-http.log`: tenant-only outbound HTTP tests, 10 passing.
- `08-mysql-resource-guards-green.log`: actual MySQL Testcontainer, 5 integration tests, 0 failures/errors/skips, build success.
- `11-binary-compatibility.log`: base-compiled legacy consumer PASS.
- `13-kernel-jdeps.log`: `java.base` only.
- `14-execution-context-descriptors.log`: canonical and legacy descriptors.
- `15-record-serialization-scan.log`: only the new record-component test matched.
- `17-affected-unit-reactor-green.log`: 16 affected reactor modules succeeded; relevant suites report 0 failures/errors/skips.
- `18-postgresql-independent-consumer-final.log`: independent Boot-parent Notes consumer with actual PostgreSQL, 2 integration tests, 0 failures/errors/skips, build success.
- `19-final-state.log`: recorded head/branch, zero dirty entries, supplied base and Ticket 01 ancestor checks passed.
- `worker-report.md`: exact commands, iteration classification, and stated limits.

The logged MySQL Docker-socket denial, the corrected Product equality assertion, the corrected PostgreSQL `Instant` fixture, the overwritten intermediate PostgreSQL log, and the sandbox loopback failure are historical iterations. The final successful logs above supersede them for this head; none is evidence of a current code failure. No broad build was repeated because the clean fixed point already has successful scoped units, both required real-database runs, binary-consumer evidence, and dependency evidence, and inspection found no concrete hypothesis requiring another broad run.

## Review commands

```text
git status --short --branch
git status --porcelain=v1
git rev-parse HEAD
git cat-file -t 4b7baf1904ae34bb63525939b8afef60bc74c8a1
git merge-base 4b7baf1904ae34bb63525939b8afef60bc74c8a1 HEAD
git log --oneline --decorate 4b7baf1904ae34bb63525939b8afef60bc74c8a1..HEAD
git log --format='%H %s' 4b7baf1904ae34bb63525939b8afef60bc74c8a1..HEAD
git diff --stat 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD
git diff --name-status 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD
git diff --check 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD
git diff --unified=100 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD -- framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContext.java framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionScope.java framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextTest.java framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java
git diff --unified=100 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD -- framework/starters/moduvera-data-mybatis-plus-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/data/mybatis/ExecutionContextTenantLineHandler.java framework/starters/moduvera-data-mybatis-plus-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/data/mybatis/ExecutionContextTenantLineHandlerTest.java framework/starters/moduvera-scheduler-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/scheduler/JobRunner.java framework/starters/moduvera-scheduler-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/scheduler/JobRunnerTest.java
git diff --unified=100 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD -- examples/simple-notes-demo/src/main/java/io/github/ande1922/moduvera/example/notes/infrastructure/persistence/MybatisPlusNoteRepository.java examples/simple-notes-demo/src/test/java/io/github/ande1922/moduvera/example/notes/NotesDemoIT.java services/catalog/catalog-service/src/main/java/io/github/ande1922/moduvera/reference/catalog/catalog/adapter/outbound/persistence/MybatisCatalogProductRepository.java services/inventory/inventory-service/src/main/java/io/github/ande1922/moduvera/reference/inventory/adapter/outbound/persistence/MybatisInventoryStore.java services/order/order-service/src/main/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/persistence/MybatisOrderRepository.java services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/persistence/MySqlBusinessRepositoriesIT.java
git diff --unified=100 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD -- services/order/order-service/src/main/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/CatalogHttpClient.java services/order/order-service/src/main/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/IdentityServiceTokenProvider.java services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/CatalogHttpClientTest.java services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/IdentityServiceTokenProviderTest.java
git diff --unified=80 4b7baf1904ae34bb63525939b8afef60bc74c8a1...HEAD -- CONTEXT.md docs/adr/0003-make-tenant-context-a-system-boundary.md docs/adr/0035-expose-authentication-and-authorization-seams-without-owning-iam.md docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md
rg -n "ExecutionContextHolder\\.require\\(\\)|\\.tenantId\\(\\)|ExecutionScope\\.platform|new ExecutionContext\\(" --glob '*.java'
rg -n "new ExecutionContext\\(\\s*null|recordComponents|getRecordComponents|Serializable.*ExecutionContext|ExecutionContext.*Serializable" --glob '*.java' --glob '*.kt' --glob '*.md'
rg -n "Tests run:|BUILD SUCCESS|BUILD FAILURE|Skipped:" /private/tmp/execution-context-frontier-20260905/evidence/02/02-kernel-three-state.log /private/tmp/execution-context-frontier-20260905/evidence/02/08-mysql-resource-guards-green.log /private/tmp/execution-context-frontier-20260905/evidence/02/17-affected-unit-reactor-green.log /private/tmp/execution-context-frontier-20260905/evidence/02/18-postgresql-independent-consumer-final.log
```
