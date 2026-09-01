# T02 — Product, Order Snapshot and Atomic Outbox

Status: **Proposed task card**  
Run kind: initial implementation  
U eligibility exercised: Product only; SalesOrder and OrderLine stay separated in both

## Candidate-visible requirement

Implement the minimum Catalog and Order path for a Store sale:

- Product create, rename, price change and enable/disable with Tenant-local persistence, Audit and CAS.
- Create an Order for one Store with exactly two requested lines whose Product IDs are distinct.
- Resolve Product name, unit price and currency through the Catalog Service API.
- Store immutable product-name/unit-price snapshots and calculate line/Order totals with `DECIMAL(19,4)` semantics.
- Persist Order as `PENDING_STOCK` and append one `ReserveInventoryCommand` Outbox intent in the same local Work Unit.
- Return/query projections; never return Domain or persistence objects.
- Provide the same observable Service API behavior through Local and HTTP Adapters.

Product is qualified for U. `SalesOrder + OrderLine`, query projections and Outbox records are not and must remain separate in both strategies.

The exact Catalog/Order/Outbox signatures are frozen in [SHARED-INTERFACE.md](../contracts/SHARED-INTERFACE.md), and all physical types, keys, indexes and message columns are frozen in [mysql-v1-baseline.sql](../ddl/mysql-v1-baseline.sql). The candidate may implement those seams but may not reshape them or the migrations.

## Public acceptance catalog

| ID | Atomic behavior |
|---|---|
| `T02-P01` | Product create returns version 0 and trusted Audit |
| `T02-P02` | price change uses expectedVersion and returns refreshed version/Audit |
| `T02-P03` | disabled Product cannot be used for a new Order |
| `T02-P04` | Order with two lines stores names, unit prices, quantities and line totals |
| `T02-P05` | Order total equals the exact sum and one currency is enforced |
| `T02-P06` | later Product rename/price change does not change existing Order snapshots |
| `T02-P07` | new Order is `PENDING_STOCK` and contains its Store ID |
| `T02-P08` | exactly one directed ReserveInventoryCommand intent is recorded with the Order |
| `T02-P09` | Order get/search returns projections filtered by Store/status/time |
| `T02-P10` | Local and HTTP Service Adapters return the same result/error semantics |

## Evaluator-only acceptance catalog

| ID | Visibility | Atomic behavior / hard gate |
|---|---|---|
| `T02-H01` | hidden | Product/Order reads and writes cannot cross Tenant |
| `T02-H02` | hidden | other-Tenant Product/Order is indistinguishable from not-found |
| `T02-H03` | hidden | a Catalog failure writes neither Order nor Outbox |
| `T02-H04` | hidden | an Outbox append failure rolls back the Order |
| `T02-H05` | hidden | Order write failure creates no Outbox record |
| `T02-H06` | hidden | independent SQL proves all physical snapshot and Audit columns |
| `T02-H07` | hidden | mixed currency and arithmetic overflow/invalid scale fail safely |
| `T02-H08` | hidden | stale Product CAS changes no row |
| `T02-H09` | hidden | HTTP/Local authorization, 404/409 and safe body are equivalent |
| `T02-H10` | hidden | query projection does not restore or expose the persistence entity |
| `T02-H11` | hidden | Outbox Tenant/Correlation/Initiator come from trusted Context |
| `T02-H12` | hidden | context is cleared after HTTP and local failure paths |
| `T02-S01` | structural | S separates Product/Order Domain from typed persistence objects |
| `T02-U01` | structural | U directly maps Product only; Order/Line/Outbox remain separated |

## Required evidence

- transaction rollback evidence for Order + Outbox;
- independent physical-column snapshot checks;
- Local/HTTP contract comparison;
- exact usage and change metrics.
