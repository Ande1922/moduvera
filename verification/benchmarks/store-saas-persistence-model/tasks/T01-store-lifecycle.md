# T01 — Store Lifecycle and Tenant Isolation

Status: **Proposed task card**  
Run kind: initial implementation  
U eligibility exercised: Store

Historical boundary: this v1 task retains its frozen explicit-tenant Repository contract so existing evidence remains reproducible. It is not the current production-interface recommendation; ADR 0003 now places routine tenant isolation behind the infrastructure seam.

## Candidate-visible requirement

Implement the Store Business Service for a chain-brand Tenant. Store is a Tenant-internal organization unit, never the Tenant isolation key.

Provide protocol-neutral create, rename, deactivate and get use cases. A Store has:

```text
tenant_id, store_id, code, name, time_zone_id,
status, version, created/updated audit
```

Rules:

- Tenant, Actor, Correlation and Clock come only from trusted execution context/dependencies.
- code is canonical uppercase, immutable and unique within one Tenant; the same code is valid in another Tenant.
- new Store is `ACTIVE`, version `0`.
- rename/deactivate Commands carry `expectedVersion`.
- a deactivated Store cannot be renamed; repeated deactivate is a state conflict.
- persistence-owned Audit and version are visible in the returned `StoreView`.
- Aggregate Repository uses `findById(TenantId, StoreId)` and returning `save(Store)`.
- every SQL read/write/CAS is Tenant-scoped.
- no persistence or ORM type crosses the Service API.

The evaluator supplies the exact [shared Interface](../contracts/SHARED-INTERFACE.md), [MySQL V1 baseline](../ddl/mysql-v1-baseline.sql), fixed Clock/IDs, public tests and the assigned X/Y persistence constraint. Do not alter shared contracts, migrations or tests.

## Public acceptance catalog

| ID | Atomic behavior |
|---|---|
| `T01-P01` | create returns canonical code, ACTIVE, version 0 and fixed trusted Audit |
| `T01-P02` | rename preserves code/created Audit and returns version + 1 with refreshed updated Audit |
| `T01-P03` | deactivate returns INACTIVE and version + 1 |
| `T01-P04` | duplicate code in one Tenant returns `store.code-conflict` |
| `T01-P05` | the same code can be created in two Tenants |
| `T01-P06` | stale expectedVersion returns `store.version-conflict` |
| `T01-P07` | a deactivated Store rejects rename and repeated deactivate with `store.state-conflict` |
| `T01-P08` | get returns a projection equal to the stored aggregate state |

## Evaluator-only acceptance catalog

| ID | Visibility | Atomic behavior / hard gate |
|---|---|---|
| `T01-H01` | hidden | other-Tenant ID is indistinguishable from not-found |
| `T01-H02` | hidden | missing Context fails before Repository access |
| `T01-H03` | hidden | denied permission fails before Repository access |
| `T01-H04` | hidden | independent SQL inspection proves Tenant predicate on select and CAS |
| `T01-H05` | hidden | concurrent stale CAS changes no row and no Audit/version |
| `T01-H06` | hidden | Audit ignores forged request fields and uses current Actor + fixed Clock |
| `T01-H07` | hidden | nanosecond Clock is normalized once to database microseconds in row and return value |
| `T01-H08` | hidden | request/message scope restores or clears Context on success and exception |
| `T01-H09` | hidden | raw physical columns match the logical DDL without using the tested Mapper |
| `T01-H10` | hidden | public types have no ORM/persistence imports or runtime instances |
| `T01-S01` | structural | S has typed Row/DO plus explicit aggregate Mapper and framework-free Domain |
| `T01-U01` | structural | U has no second Store Row/DO/object Mapper and keeps mapping metadata in Infrastructure |

## Required evidence

- public and hidden atomic assertion report;
- raw independent SQL observations;
- source structural scan and Domain import list;
- exact Codex JSONL usage;
- diff/file/LOC/interface/mapped-field metrics.
