# T03 — Store Inventory CAS, Idempotency and Inbox/Outbox

Status: **Proposed task card**  
Run kind: initial implementation  
U eligibility exercised: none; pre-registered negative control

## Candidate-visible requirement

Implement Inventory owned by `tenant + store + product`:

- adjust available quantity with a stable command ID and expected stock version;
- reserve two distinct Product lines atomically for one Store, processed in ascending Product-ID order;
- reject insufficient stock without partial deduction;
- replay a duplicate API command by returning the stored outcome without changing quantity or version again; a duplicate message delivery is skipped by Inbox and emits no second Outbox;
- persist the reservation outcome and publish `InventoryReserved` or `InventoryRejected` through Outbox;
- consume through a Tenant-aware Inbox in the same Work Unit as business change and result Outbox;
- use SQL CAS and trusted Context/Audit.

InventoryStock is frozen as not eligible for U in this Pilot because reservation spans stock rows, deduplication, outcome records and asynchronous reliability. U must not force it into a direct-mapped simple entity.

Both labels therefore receive the same required persistence shape. T03 is scored for correctness and experimental drift but excluded from the S-versus-U efficiency effect. A label-correlated cost difference is a batch-validity warning, not a persistence-model result.

The exact `InventoryApi`, deep `InventoryStore`, Inbox and result-Outbox signatures are frozen in [SHARED-INTERFACE.md](../contracts/SHARED-INTERFACE.md). Adjustment replay must use the frozen `inventory_adjustment` table keyed by `(tenant_id, command_id)`; reservation replay must use `inventory_reservation`; reliability records use the exact `message_inbox`/`message_outbox` templates in [mysql-v1-baseline.sql](../ddl/mysql-v1-baseline.sql). No alternate dedup table, stock-row command field or generic JSON state is allowed.

## Public acceptance catalog

| ID | Atomic behavior |
|---|---|
| `T03-P01` | positive adjustment creates/increments stock and returns refreshed version/Audit |
| `T03-P02` | negative adjustment cannot make available quantity negative |
| `T03-P03` | successful two-line reserve deducts both quantities exactly once |
| `T03-P04` | insufficient line rejects the whole reservation and deducts nothing |
| `T03-P05` | duplicate reserve returns the original outcome and changes no stock version |
| `T03-P06` | duplicate adjustment changes quantity/version once only |
| `T03-P07` | successful result is `InventoryReserved`; failure is `InventoryRejected` business data |
| `T03-P08` | one result Outbox intent is committed with the final reservation outcome |

## Evaluator-only acceptance catalog

| ID | Visibility | Atomic behavior / hard gate |
|---|---|---|
| `T03-H01` | hidden | deduplication identity is `(tenant_id, command/message_id)` |
| `T03-H02` | hidden | same command ID may be used independently in another Tenant |
| `T03-H03` | hidden | no stock select/update/CAS omits Tenant or Store/Product key |
| `T03-H04` | hidden | two concurrent reservations cannot oversell |
| `T03-H05` | hidden | stale adjustment CAS changes no row/Audit/version |
| `T03-H06` | hidden | crash/exception before commit writes neither stock outcome nor Inbox/Outbox |
| `T03-H07` | hidden | result-Outbox failure rolls back stock, reservation and Inbox |
| `T03-H08` | hidden | duplicate API command after commit returns persisted outcome without new Outbox; duplicate delivery skips the Inbox callback |
| `T03-H09` | hidden | forged Tenant/Actor/time fields are ignored |
| `T03-H10` | hidden | message Context restores Tenant/Correlation/Initiator, executes as trusted Actor, then clears |
| `T03-H11` | hidden | independent SQL verifies quantity, version, Audit and reservation-line rows |
| `T03-H12` | hidden | missing Context and denied permission fail before business access |
| `T03-N01` | structural | both labels keep InventoryStock, adjustment/reservation records, Inbox/Outbox and projections in the same frozen separated shape |

## Required evidence

- concurrent CAS/oversell trace;
- duplicate delivery and rollback evidence;
- independent SQL for every affected physical table;
- exact usage and structural qualification evidence.
