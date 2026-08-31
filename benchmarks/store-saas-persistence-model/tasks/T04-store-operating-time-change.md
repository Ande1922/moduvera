# T04 — Fixed Change: Store Operating-Time Value Object

Status: **Proposed task card**  
Run kind: fixed change against canonical green T01 baselines  
U eligibility after change: Store remains eligible

## Starting baseline

The evaluator owns one canonical green S baseline and one canonical green U baseline for T01. Both must pass the complete T01 public/hidden catalog before this task. Candidate-generated T01 output is not reused, so initial-run noise does not contaminate change amplification.

The two baselines share the same Interface, behavior, DDL and source-generation policy; only the registered S/U persistence representation differs. Their seed digests are paired in the run manifest.

## Candidate-visible change request

Replace Store's single time-zone value with a `StoreOperatingTime` value object:

```text
StoreOperatingTime
  ZoneId zoneId
  LocalTime businessDayCutoff
```

Requirements:

- add scalar column `business_day_cutoff TIME(6) NOT NULL` next to `time_zone_id`;
- migrate existing rows to cutoff `04:00:00` without changing other data/Audit/version;
- replace `CreateStoreCommand` with the four-component record and retain the exact three-argument delegating constructor defined in the frozen contract; that constructor supplies `04:00:00`;
- add `changeOperatingTime(storeId, zoneId, cutoff, expectedVersion)`;
- expose zone/cutoff in StoreView;
- add the exact Domain method `LocalDate StoreOperatingTime.businessDate(Instant instant)`: local calendar date when local time is at/after cutoff, otherwise previous local date;
- preserve Tenant, RBAC, Audit, CAS, error, Local/HTTP and projection rules;
- Store remains one-table with stable scalar conversion and therefore remains U-qualified.

The complete allowed Interface delta is frozen in [SHARED-INTERFACE.md](../contracts/SHARED-INTERFACE.md): one `StoreApi` method, one four-component create record with the compatibility constructor, one change command, and the cutoff component added to `StoreView`. The exact migration is [mysql-v2-store-operating-time.sql](../ddl/mysql-v2-store-operating-time.sql), applied after the separately checksummed [V1 baseline](../ddl/mysql-v1-baseline.sql). Candidates may not choose a different overload/default/migration shape.

## Public acceptance catalog

| ID | Atomic behavior |
|---|---|
| `T04-P01` | existing row migration supplies 04:00 cutoff without altering Audit/version |
| `T04-P02` | new Store persists and returns the explicit zone/cutoff |
| `T04-P03` | operating-time change uses expectedVersion and refreshed Audit/version |
| `T04-P04` | instant before cutoff maps to previous local business date |
| `T04-P05` | instant at/after cutoff maps to current local business date |
| `T04-P06` | get projection contains values equal to physical columns |

## Evaluator-only acceptance catalog

| ID | Visibility | Atomic behavior / hard gate |
|---|---|---|
| `T04-H01` | hidden | versioned migration is deterministic and a second migrator run skips the already-applied version |
| `T04-H02` | hidden | invalid ZoneId/cutoff fails before persistence |
| `T04-H03` | hidden | DST gap/overlap cases use `Instant -> ZoneId` conversion without invented local instants |
| `T04-H04` | hidden | stale CAS and other-Tenant update alter no row |
| `T04-H05` | hidden | created Audit remains stable and updated Audit is trusted/microsecond-normalized |
| `T04-H06` | hidden | Local and HTTP results/errors remain equivalent |
| `T04-H07` | hidden | physical SQL columns are checked without the candidate Mapper |
| `T04-S01` | structural | S updates typed Row/DO and explicit value-object mapping consistently |
| `T04-U01` | structural | U still has no second Store persistence object/object Mapper |

## Change-amplification measurements

- files and handwritten/generated LOC changed from the canonical baseline;
- public Interface additions and incompatible changes;
- duplicate mapped fields touched;
- compile/test commands and repair turns;
- first-pass regressions outside T04;
- exact Tokens and time to first complete green.
