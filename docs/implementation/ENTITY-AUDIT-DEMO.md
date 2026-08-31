# Entity Audit Model Demo

Status: **Historical experiment; the JDBC mapping remains evidence, but its tenant-bearing Domain candidates are superseded by ADR 0003's tenant-transparent business model.**

This Demo must not be used as the production Order model. It compared audit lifecycles under an older assumption that every tenant-owned Domain entity carries `TenantId`. The accepted target now keeps `tenant_id` explicit in persistence records and database constraints while ordinary Application, Domain and Repository interfaces obtain technical isolation from trusted infrastructure context. The mapping and database observations below remain historical evidence only.

This work contains two different evidence levels:

1. an in-memory lifecycle probe that compares three ways to place technical audit state around a tenant-owned Domain entity;
2. a real JDBC persistence integration test for Variant B that flattens Domain value objects into scalar columns in MySQL and PostgreSQL.

The first probe is not database evidence. It stores nested Java objects in a `HashMap`. The second test executes real DDL, parameterized SQL and result-set restoration.

Source:

- Lifecycle only: `services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/design/EntityAuditModelDemoTest.java`
- Candidate Domain: `services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/design/domain/`
- Flat persistence Row and JDBC Adapter: `services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/design/persistence/`
- MySQL DDL: `services/order/order-service/src/test/resources/db/entity-audit-demo/mysql.sql`
- PostgreSQL DDL: `services/order/order-service/src/test/resources/db/entity-audit-demo/postgresql.sql`

Run with JDK 26:

```bash
./mvnw -pl services/order/order-service -am \
  -Dtest=EntityAuditModelDemoTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Execution evidence on 2026-08-30: JDK 26 compiled the probe and all three tests passed (`3` run, `0` failures, `0` errors, `0` skipped). This proves that all three lifecycles are implementable; it does not make them equally safe or ergonomic.

The real persistence test was then executed against `mysql:8.4.11` and `postgres:18.6`. Both database cases passed (`2` run, `0` failures, `0` errors, `0` skipped). The test proves:

- DDL creates ten scalar columns;
- `TenantId.value` is bound to `tenant_id`;
- `AuditMetadata` and nested `AuditActor` values are flattened to six audit columns;
- `INSERT` and `SELECT` round-trip the candidate Domain object;
- update preserves created audit, replaces updated audit and advances `version`;
- a stale version is rejected by the SQL compare-and-set predicate.
- nanosecond `Instant` inputs are truncated at the Adapter seam to the microsecond precision supported by both DDLs, so the returned aggregate and stored row do not diverge;
- the test performs an additional raw JDBC `SELECT` outside the Adapter/Mapper and asserts every physical column, then restores the complete Domain again after update.

This still does not prove the selected MyBatis-Plus adapter. The verified Adapter is explicit JDBC so that every conversion and bound parameter remains visible.

## Shared constraints

- Domain types remain free of Spring, jOOQ and MyBatis-Plus.
- IDs exist before entity creation.
- Tenant ownership is explicit in persistence records, constraints and infrastructure context; it is not automatically a Domain field or ordinary Repository parameter.
- Actor and time originate from a trusted execution context and clock.
- The MyBatis-Plus Adapter must implement the same Repository behavior on PostgreSQL and MySQL, with PostgreSQL as the Golden Path.
- Optimistic versioning is enabled per aggregate, not by a universal base field.
- There is no universal soft-delete field.

## Variant A: audit outside Domain

`BaseEntity<ID>` owns identity and `TenantEntity<ID>` adds `TenantId`. The in-memory Adapter obtains the audit stamp and stores it in a nested Java `OrderRow`. The Domain entity never exposes technical audit state. This Variant has not crossed a database seam.

Benefits:

- Business constructors and mutation methods do not accept technical audit arguments.
- A `void save(entity)` Repository remains honest because the Domain object does not promise current audit state.
- A future MyBatis-Plus Adapter may represent columns differently without changing Domain; the lifecycle probe does not verify that claim.

Cost:

- A caller that genuinely needs audit metadata must use a Query projection or a separate explicitly audited view.
- Domain-to-record mapping repeats audit columns that do not exist in Domain.

## Variant B: immutable audit inside Domain, owned by persistence

`BaseEntity<ID>` also exposes immutable `AuditMetadata`. The Adapter remains the authority that creates and updates the metadata.

This makes `save` return the refreshed aggregate:

```java
order = repository.save(order);
```

Benefits:

- Loaded Domain entities expose audit information without another projection.
- Audit values still come from one trusted Adapter path.

Cost:

- Ignoring the return value leaves the caller with a stale audit snapshot.
- Every restore/copy path must carry audit state even when no business rule uses it.
- The Repository interface now promises post-save refresh semantics for all entities.

Variant B is the only candidate that currently has real database evidence. Its persistence Row contains only scalar values:

```java
record OrderRow(
    String tenantId,
    long orderId,
    String status,
    long version,
    Instant createdAt,
    String createdByType,
    String createdById,
    Instant updatedAt,
    String updatedByType,
    String updatedById
) {}
```

The explicit Mapper performs the flattening and reconstruction:

| Domain path | Persistence Row | Database column |
|---|---|---|
| `order.tenantId().value()` | `tenantId` | `tenant_id` |
| `order.id()` | `orderId` | `order_id` |
| `order.status().name()` | `status` | `status` |
| `order.version().value()` | `version` | `version` |
| `order.audit().createdAt()` | `createdAt` | `created_at` |
| `order.audit().createdBy().type().name()` | `createdByType` | `created_by_type` |
| `order.audit().createdBy().subjectId()` | `createdById` | `created_by_id` |
| `order.audit().updatedAt()` | `updatedAt` | `updated_at` |
| `order.audit().updatedBy().type().name()` | `updatedByType` | `updated_by_type` |
| `order.audit().updatedBy().subjectId()` | `updatedById` | `updated_by_id` |

## Variant C: audit inside Domain, owned by Application

The Application obtains a trusted `AuditStamp` and supplies it when creating or mutating the entity:

```java
Order order = Order.place(tenantId, id, auditStamp);
order.confirm(nextAuditStamp);
repository.save(order);
```

Benefits:

- The in-memory object always contains current audit values.
- `void save(entity)` remains sufficient and both persistence Adapters are simple.

Cost:

- Audit enters every business mutation interface or requires a separate `touch` call that callers can forget.
- Application code becomes responsible for a technical lifecycle that was previously local to persistence.
- Tests must construct trusted audit stamps even when exercising unrelated business rules.

## What this Demo can establish

The lifecycle probe exposes interface differences. The JDBC test proves that nested Domain values can be flattened into and restored from real scalar columns on both supported databases. Neither test demonstrates a meaningful runtime-performance difference between the three Domain layouts; a microbenchmark of toy entities would not predict repository throughput.

The production decision should therefore be based on ownership and caller ergonomics. If audit fields are placed in Domain, the decision must also select either persistence-owned refresh semantics or Application-owned audit mutation; merely adding the fields leaves their lifecycle undefined.

## Historical design read

The evidence does not show a material efficiency reason to exclude Audit from Domain. Only Variant B has crossed the real database seam, so no claim is made that all three variants have verified-identical SQL.

Before ADR 0003 was refined, the strongest candidate in this experiment was Variant B with a narrow scope:

- `BaseEntity<ID>` contains ID and immutable `AuditMetadata`.
- `TenantEntity<ID>` adds `TenantId`.
- Only independently persisted, audited entities use these base types; value objects, line items and link rows do not inherit them mechanically.
- Domain behavior may read Audit but does not manufacture trusted actor/time values or expose arbitrary audit setters.
- The persistence Adapter captures the trusted audit stamp, and `save` returns the refreshed aggregate. The calling Transaction Boundary must use that returned value.
- `AggregateVersion` remains explicit on aggregates that need optimistic locking; it is not a base field.
- Soft deletion remains business-specific.

This candidate accepts Audit as useful entity metadata while keeping Spring Security principals, execution-context lookup, ORM annotations, database defaults and field-filling mechanics outside Domain. Its remaining risks are the `save` return-value discipline and MyBatis-Plus mapping ergonomics; the PostgreSQL/MySQL Repository TCK must verify both before the candidate becomes an accepted decision.
