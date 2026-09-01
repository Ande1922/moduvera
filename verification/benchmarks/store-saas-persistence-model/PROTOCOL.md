# Store SaaS S/U Persistence-Model Benchmark Protocol

Status: **Proposed**  
Protocol version: `0.1.0-proposed`  
Scope: non-deployment architecture evaluation

Historical boundary: this v1 protocol freezes explicit `TenantId` Domain/Repository shapes for reproducibility. ADR 0003 was later refined so ordinary production business interfaces are tenant-transparent and infrastructure derives isolation from trusted context. Do not use this frozen benchmark contract as the production tenant-interface template; a future v2 benchmark must encode the revised seam without rewriting v1 evidence.

## Outcome

Measure whether a second persistence object and explicit object Mapper improve or harm AI-assisted development for a representative multi-tenant Store SaaS system. Correctness is a hard gate. Token, time and change-cost comparisons are meaningful only after both variants satisfy the same public and hidden acceptance catalog.

This protocol deliberately does not seek one universal winner. A valid result may be a selection rule such as “U for qualified simple single-table entities; S for complex aggregates; projections and message records remain separate in both.”

## Current evidence boundary

The current repository already confirms several inputs but not the benchmark outcome:

- Tenant is the chain brand/operator and Store is a Tenant-internal organization unit ([CONTEXT.md](../../../CONTEXT.md)).
- Tenant Context is an accepted fail-closed system contract ([ADR 0003](../../../docs/adr/0003-make-tenant-context-a-system-boundary.md)).
- Aggregate Repository belongs to Domain and Query Repository belongs to the Application read side ([ADR 0006](../../../docs/adr/0006-use-simplified-ddd-with-explicit-application-services.md)).
- MySQL/PostgreSQL and jOOQ/MyBatis-Plus earn support only through shared TCKs ([ADR 0008](../../../docs/adr/0008-support-two-databases-and-two-data-adapters-as-a-tested-matrix.md)).
- The current production Catalog and Order repositories still use `void save`; returning-save Audit/CAS is only an experimental candidate ([ENTITY-AUDIT-DEMO.md](../../../docs/implementation/ENTITY-AUDIT-DEMO.md)).
- The JDBC Demo proved tenant-scoped scalar columns, explicit mapping, microsecond normalization and version CAS on MySQL/PostgreSQL. It did not prove MP/jOOQ parity or select a production model.
- The repository has no Git `HEAD`; all current scaffold files are untracked. No immutable experimental seed exists.

The existing production layout and JDBC Demo bias toward S and must not be copied into a candidate-visible neutral seed.

## Research question and hypotheses

Primary question:

> With business Interface, behavior, DDL, database, data Adapter, model, reasoning effort, tools and tests held constant, how does eliminating the second persistence object for pre-qualified simple Domain entities affect exact Tokens to Green, correctness and change amplification?

Pre-registered hypotheses:

- `H1`: U reduces handwritten files, duplicate fields, mapping LOC and Tokens to Green for Store and Product.
- `H2`: S provides better Locality when nested value objects or persistence shape change.
- `H3`: neither strategy should change tenant, RBAC, Audit, CAS, Outbox/Inbox or idempotency correctness; any difference is a defect, not an accepted trade-off.
- `H4`: forcing complex aggregates into U will increase defects; therefore eligibility is frozen before results and is itself evaluated.

## Interface design comparison

Three read-only designs were developed before selecting the shared candidate:

1. **Minimum surface** — one `StoreApi` with three entry points and one `StoreRepository` with `findById + save`. It maximizes leverage per entry point but requires transport subtype dispatch if multiple changes share one command.
2. **Maximum flexibility** — explicit create/rename/deactivate/get methods plus a separate Query Repository. It keeps projections and future search changes local but slightly enlarges the Service API.
3. **Default caller first** — explicit use cases, batched Catalog reads and a deep `InventoryStore` that hides multi-row atomicity, idempotency and CAS. It is easiest for Application callers but has a larger business Interface.

Recommendation: use a hybrid. Keep explicit business use cases, aggregate-specific `findById + returning save`, separate Query Repositories and the deeper `InventoryStore`. Do not create a generic CRUD Repository, Base Mapper, `port(s)` package or persistence-shaped public DTO.

## Shared observable Interface candidate

This section is a proposed freeze for the benchmark seed, not an accepted production decision.

The binding, candidate-visible signatures are frozen in [contracts/SHARED-INTERFACE.md](./contracts/SHARED-INTERFACE.md). The neutral seed must provide those exact source contracts for both variants. The examples below explain the design; they do not authorize candidates to invent alternate Product, Order, Inventory, Query, Inbox or Outbox seams.

### Service API

- Commands and Queries never accept `tenantId`, Actor, Audit, Correlation or current time from a caller.
- Entrypoints require a trusted `ExecutionContext`, perform use-case RBAC, then obtain Tenant/Actor/Correlation from that Context.
- Update Commands carry `expectedVersion`; Views expose the version used to form the next command.
- Public and internal responses are projections. Domain entities, persistence objects, ORM types and generated records never cross the Service API Seam.
- Local and HTTP Adapters implement the same Service API and preserve errors and authorization semantics.

Representative Store Interface:

```java
interface StoreApi {
    StoreView create(CreateStoreCommand command);
    StoreView rename(RenameStoreCommand command);
    StoreView deactivate(DeactivateStoreCommand command);
    StoreView get(GetStoreQuery query);
}

record CreateStoreCommand(String code, String name, String timeZoneId) {}
record RenameStoreCommand(long storeId, String name, long expectedVersion) {}
record DeactivateStoreCommand(long storeId, long expectedVersion) {}
record GetStoreQuery(long storeId) {}
```

Store code is canonical uppercase `[A-Z][A-Z0-9_-]{0,31}`, immutable after creation and unique within a Tenant. The same canonical code may exist in different Tenants. Store name is trimmed and 1–128 characters. Time zone is a canonical IANA Zone ID. A deactivated Store cannot be renamed; repeated deactivation is a state conflict.

### Domain metadata and Repository Seam

```java
abstract class BaseEntity<ID> {
    ID id();
    AuditMetadata audit();
}

abstract class TenantEntity<ID> extends BaseEntity<ID> {
    TenantId tenantId();
}

interface StoreRepository {
    Optional<Store> findById(TenantId tenantId, StoreId storeId);
    Store save(Store candidate);
}
```

Rules:

- `BaseEntity<ID>` contains identity and immutable Audit metadata only. `TenantEntity<ID>` adds Tenant ownership.
- Only independently persisted audited entities use these types. Value objects, line items, link rows, projections, Outbox and Inbox records do not inherit them mechanically.
- `AggregateVersion` stays on concrete aggregates that need optimistic locking; it is not a universal base field.
- A draft has a pre-generated positive ID, version `0` and `AuditMetadata.unpersisted()`.
- Domain mutation never manufactures Actor/time and does not increment the stored version.
- `save(draft)` captures trusted current Actor plus injected Clock, normalizes time to database microseconds, inserts version `0`, and returns the exact persisted entity.
- `save(existing)` performs `tenant_id + aggregate_id + expected_version` SQL CAS, preserves created Audit, refreshes updated Audit, increments version and returns the exact persisted entity.
- The Application caller must use the returned entity. Continuing with the candidate is a correctness defect.
- The explicit Tenant argument and entity Tenant must both equal the current trusted Context Tenant. Missing Context or mismatch fails before SQL.
- Every select/update/delete/CAS predicate includes `tenant_id`. A zero-row update may distinguish not-found from conflict only by querying the current Tenant; it may never probe another Tenant.
- Database/ORM/constraint exceptions are translated inside the Persistence Adapter.

Audit means the current executing Actor. Initiator remains separate message-envelope metadata. If the product later needs original-initiator audit, it must add an explicit field rather than reinterpret `created_by` differently in S and U.

Query Repositories return projections directly and are separate from Aggregate Repositories. Inventory remains a deeper business Module:

```java
interface InventoryStore {
    InventoryStockView adjust(TenantId tenantId, AdjustInventoryCommand command, Instant now);
    InventoryReservationExecution reserve(TenantId tenantId, ReserveInventoryCommand command,
                                          MessageId proposedResultMessageId, Instant now);
    Optional<InventoryStockView> find(TenantId tenantId, StoreId storeId, ProductId productId);
}
```

It hides duplicate-command replay, multi-line atomicity, insufficient-stock decisions, reservation result persistence and stock-row CAS.

### Stable error semantics

| Condition | Stable code/result | HTTP |
|---|---|---:|
| invalid request/value | `request.validation-failed` | 400 |
| missing trusted Context | fail closed before use case | 401/403 by entry contract |
| denied permission | `security.permission-denied` | 403 |
| missing or other-Tenant Store | `store.not-found` | 404 |
| Tenant-local Store code conflict | `store.code-conflict` | 409 |
| stale expected version or SQL CAS | `store.version-conflict` | 409 |
| illegal Store state transition | `store.state-conflict` | 409 |
| missing Product/Order | service-owned `*.not-found` | 404 |
| stale Product/Order version | service-owned `*.version-conflict` | 409 |
| insufficient stock | `InventoryRejected` business result | 200/accepted message result |
| unclassified persistence failure | safe `*.persistence-failure` | 500 |

Cross-Tenant access and genuine absence have indistinguishable public behavior. Raw SQL, constraint names and ORM exceptions never appear in the safe error body.

## The single intended variable

### S — Separated Persistence Model

- Framework-free Domain entity/aggregate.
- Infrastructure-owned flat typed Row/DO or generated record.
- Aggregate-specific object Mapper expands value objects to scalar persistence fields and restores them.
- ORM annotations, SQL, column names, database precision and exception translation remain in Infrastructure.

### U — Unified Mapped Entity

- A qualified simple Domain entity is the object mapped by the Infrastructure Adapter.
- No second Row/DO object and no Domain-to-Row object Mapper may exist for that entity.
- The first Pilot keeps Domain framework-free and places MyBatis result maps, SQL, TypeHandlers and conversion metadata in Infrastructure. This isolates object duplication from annotation coupling.
- An annotation-driven U variant is a separate future factor. It must not be mixed into the first S/U Pilot or used to change the accepted production ArchUnit rule.
- Complex aggregates, projections, Outbox/Inbox and public DTOs remain separate exactly as in S.

Both strategies may contain persistence mapping metadata. The experimental difference is whether the mapped persistence object is the qualified Domain entity itself or a second typed object that requires object mapping.

### Eligibility frozen before results

| Model | U eligibility | Reason |
|---|---|---|
| Store | eligible | one main table, scalar/stable conversions, simple lifecycle |
| Product | eligible | one main table, scalar Money conversion, simple lifecycle |
| InventoryStock | not eligible in Pilot | reservation, deduplication, multi-row atomicity and CAS form a deeper Module |
| SalesOrder + OrderLine | not eligible | owned collection, snapshots and asynchronous state lifecycle |
| Query projection | never a Domain entity | direct read model in both |
| Outbox/Inbox | never a Domain entity | reliability records in both |

Eligibility may not be changed after seeing results. U fails its design constraint if it introduces a shadow Row/DO, a generic `Map`/JSON row, or an object Mapper for an eligible entity.

## Frozen logical DDL

The binding first-Pilot baseline is [ddl/mysql-v1-baseline.sql](./ddl/mysql-v1-baseline.sql); T04 alone adds [ddl/mysql-v2-store-operating-time.sql](./ddl/mysql-v2-store-operating-time.sql). S and U receive the same ordered migration bytes and per-version checksums; candidates may not edit them. IDs are `BIGINT`; Tenant IDs are portable 1–64 character, case-sensitive strings; Actor subject IDs are 1–128 characters; money is `DECIMAL(19,4)`; time is UTC at microsecond precision. A later PostgreSQL experiment must mechanically preserve these logical columns, keys and constraints while using `TIMESTAMPTZ(6)` rather than MySQL `DATETIME(6)`.

- `store_location`: `tenant_id, store_id, code, name, time_zone_id, status, version, audit columns`; PK `store_id`; unique `(tenant_id, store_id)` and `(tenant_id, code)`.
- `catalog_product`: `tenant_id, product_id, name, unit_price, currency_code, status, version, audit columns`; PK `product_id`; unique `(tenant_id, product_id)`.
- `inventory_stock`: `tenant_id, store_id, product_id, available_quantity, version, audit columns`; PK `(tenant_id, store_id, product_id)`; quantity non-negative.
- `inventory_adjustment`: immutable request and resulting stock quantity/version keyed by `(tenant_id, command_id)`; this is the sole T03 adjustment-replay record.
- `inventory_reservation`: `tenant_id, command_id, order_id, store_id, outcome, completed_at`; PK `(tenant_id, command_id)`.
- `inventory_reservation_line`: `tenant_id, command_id, product_id, requested_quantity, outcome`; PK `(tenant_id, command_id, product_id)`; Tenant-aware FK to reservation.
- `sales_order`: `tenant_id, order_id, store_id, status, currency_code, total_amount, placed_at, version, audit columns`; PK `order_id`; unique `(tenant_id, order_id)`.
- `sales_order_line`: `tenant_id, order_id, line_no, product_id, product_name, unit_price, quantity, line_total`; PK `(tenant_id, order_id, line_no)`; Tenant-aware FK to sales order.
- Each asynchronous service instantiates the exact `message_outbox` and `message_inbox` templates. Inbox identity is `(tenant_id, message_id)`; Outbox identity is also Tenant-scoped and its dispatch index, lease fields and envelope columns are frozen in the SQL.

Audit columns are `created_at, created_by_type, created_by_id, updated_at, updated_by_type, updated_by_id`. There is no universal soft-delete column. Store/Product/Order/Inventory belong to separate Business Services, so there are no cross-service foreign keys for `store_id` or `product_id`.

## Pilot scope

The first scored Pilot fixes:

- Adapter: MyBatis-Plus;
- database: MySQL;
- four task cards in [tasks/](./tasks/);
- three fresh candidate sessions per strategy per task;
- total: `4 × 2 × 3 = 24` candidate runs, before repair turns;
- at most two standardized repair turns per run (proposed stop condition);
- no JPA, jOOQ, PostgreSQL, deployment assets or production ADR changes.

All scored runs use the same preheated Maven dependencies, JDK and database image. Cold dependency/image calibration is an unscored harness measurement and is reported separately. Model prompt-cache behavior is not assumed: raw `cached_input_tokens` is retained for every turn.

The Pilot supports only an MP+MySQL conclusion. Full support-matrix claims require later paired experiments.

T03 is a **pre-registered negative control**: no entity is U-eligible, so the two candidate constraints demand the same persistence shape. Its six candidate runs (three X/Y pairs) test prompt-label neutrality and compliance with the eligibility rule. T03 correctness and cost are reported separately and are excluded from the S-versus-U efficiency effect for T01/T02/T04. A systematic X/Y T03 difference invalidates the affected batch as experimental drift; it is not evidence for either persistence model.

## Correctness hard gates

The following defects invalidate a run for efficiency comparison even if most tests pass:

- cross-Tenant read, write, existence or error-shape leakage;
- select/update/delete/CAS without Tenant scope;
- missing Context does not fail closed;
- Context is not restored/cleared after HTTP, message or task execution;
- Inbox or command deduplication is not Tenant-aware;
- duplicate command/message changes inventory twice;
- partial multi-line inventory reservation;
- stale CAS succeeds or version/audit returned by `save` differs from storage;
- money, currency or Product snapshot drift;
- Audit Actor/time/Tenant comes from untrusted request fields;
- business state and Outbox, or business state and Inbox, are not atomic;
- public contract exposes a persistence object or ORM type;
- shared hidden acceptance is not fully green.

A run with any hard-gate failure is reported but excluded from Tokens-to-Green and efficiency winner claims.

## Public and hidden evaluator isolation

- A neutral immutable seed contains only shared contracts, logical DDL, fixtures, public tests and runner hooks. It contains no S/U implementation answer.
- The current `ENTITY-AUDIT-DEMO` and existing persistence-shaped examples are excluded from candidate-visible files.
- Candidate workspaces are created from the same seed digest. Only the variant constraint paragraph differs.
- A scored candidate workspace is physically outside the evaluator repository. Codex command tools use a beta permission profile that denies the filesystem root by default, reopens only the candidate workspace plus pinned JDK/Maven/read-only dependency paths, writes only the candidate allowlist and `target`, and disables command networking. Plain `workspace-write` is insufficient because it permits reads outside the candidate seed.
- Before any scored model call, an out-of-sandbox host probe must prove the positive and negative read/write/network cases for the exact permission-profile digest and candidate path. A missing or stale probe keeps the manifest `BLOCKED`.
- Candidate subprocesses inherit no ambient shell environment. The runner explicitly sets JDK 26 `JAVA_HOME`/`PATH`, offline Maven repository, candidate-local `TMPDIR` and UTC; both `java -version` and `mvn -version` must independently report Java 26.
- Candidates see a generated task brief and public tests. They cannot read the evaluator repository, hidden tests, alternate variant workspace, alternate conversation, prior patch or repair outcome.
- Hidden tests execute from a physically separate evaluator path after the candidate turn. Physical-column assertions use independent SQL, never the tested Mapper.
- Public and hidden suites cross the same Service/Repository Interface for both variants. Variant-specific tests may only check adherence to the S/U structural constraint; they cannot replace shared behavior tests.
- A failed hidden run produces a frozen sanitized repair brief: failing acceptance IDs, severity and bounded observed behavior. It never reveals hidden source or the other variant.
- Repair turns resume only their own candidate session. A run ends at green, after two repair turns, on infrastructure failure, or on a hard stop.
- Complete raw logs are retained locally; candidate-facing logs use standardized success and bounded failure summaries.

## Exact Token measurement

Primary metric: **Tokens to Green** — cumulative model usage from the initial candidate turn through the first turn after which all public and hidden atomic assertions pass.

For each Codex `turn.completed` event retain:

- `input_tokens`;
- `cached_input_tokens` as a subset of input;
- `output_tokens`;
- `reasoning_output_tokens` as a subset/breakdown of output;
- raw event and CLI version.

Normalization:

```text
uncached_input_tokens = input_tokens - cached_input_tokens
gross_total_tokens    = input_tokens + output_tokens
visible_output_tokens = output_tokens - reasoning_output_tokens
```

Cached input and reasoning breakdown are never added again. This matches the Responses usage shape, where cached tokens are input details and reasoning tokens are output details ([official OpenAI Responses reference](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)). OpenAI's Codex non-interactive documentation also defines `codex exec --json` as JSONL and shows the same four usage fields in `turn.completed` ([official Codex non-interactive documentation](https://developers.openai.com/codex/noninteractive)). If a CLI version changes field semantics or omits required usage, the run is invalid; character count or log-length estimates are forbidden.

The authorized two-turn initial+exact-ID-resume probe on `codex-cli 0.144.4` established **session-cumulative** semantics. Turn 1 reported `23652` input / `6` output; the resumed event reported cumulative `47774` input / `12` output, so turn 2 is the delta `24122` input / `6` output. Scored extraction must therefore validate monotonic counters and use successive deltas / the final cumulative event. See the [resume calibration evidence](./evidence/probes/2026-08-30-codex-cli-resume-usage/summary.json).

`extract_codex_usage.py` still defaults to one-event mode and requires the explicit calibrated `--usage-semantics session-cumulative` for multiple ordered JSONL files. A CLI version change invalidates this calibration until the same two-turn probe is repeated.

Report initial-turn, repair-turn and final cumulative usage, plus `gross_total_tokens / final passed atomic assertions`. Harness/spec construction and hidden evaluator execution are excluded from candidate Tokens to Green but measured separately as harness cost.

Every result must also pass [scripts/validate_result_semantics.py](./scripts/validate_result_semantics.py). This derives first/final pass counts and hard-gate IDs from `attempts[*].assertions`, checks final process/test exit codes, and enforces usage arithmetic, attempt totals, assertion rates and status consistency that Draft 2020-12 JSON Schema cannot express. In particular, `GREEN` requires every frozen atomic assertion green, no hard-gate failure, final agent/public/hidden exit codes `0`, and `tokens_to_green == usage_total.gross_total_tokens`; every non-green result is excluded and has `tokens_to_green = null`. A CLI failure before thread creation is a valid `INFRA_FAILURE` with nullable attempt thread/usage or no attempt, but it cannot enter efficiency analysis.

The first Step 0 probe proved that local `codex-cli 0.144.4 --json` emits the required fields. A trivial `OK` run still consumed 23,084 input tokens, demonstrating why gross implicit context must be retained. See [single-turn probe evidence](./evidence/probes/2026-08-30-codex-cli-usage/summary.json).

## Other measurements

- first-pass and final atomic assertion pass rate;
- repair turns and compile/test command count;
- defects deduplicated by evaluator root cause: P1 tenant/data corruption, P2 behavior/contract, P3 maintainability;
- wall clock, agent/tool active time, Maven time and Testcontainers time separately;
- files read, files changed, handwritten LOC, generated LOC;
- public Interface surface, Domain framework imports, duplicate mapped fields;
- human clarification count;
- fixed-change amplification: changed files, changed Interface, regression defects, Tokens/time to first green;
- raw run values, paired deltas, median and IQR. Pilot sample sizes are descriptive, not inferential.

Mutation score may measure evaluator sensitivity but is not an implementation-defect count.

## Randomization and reproducibility

- Candidate-facing labels are `X` and `Y`; the evaluator privately maps them to S/U per pair.
- Pair order is generated from a recorded random seed and alternates where possible to reduce time drift.
- Model ID, reasoning effort and service tier are explicitly pinned in argv/config overrides and recorded with their effective values; Codex version, system/developer instructions, AGENTS content, skill inventory, tool permissions, visible-file digest, prompt digest, JDK, Maven, OS/architecture, database image digest and cache state are also recorded before the run. The relevant override keys are defined by the [official Codex configuration reference](https://developers.openai.com/codex/config-reference).
- Every candidate run is a fresh session. Repair turns remain in that run's session only.
- Initial and repair commands repeat the exact model, reasoning, tier, approval, feature, permission-profile and shell-environment overrides. Repairs use the exact recorded session ID and candidate cwd; `--last`, `--ephemeral` and `--sandbox` are prohibited because they respectively risk session crossover, destroy resumability, or override the permission profile.
- The exact command argv is stored without credentials. Secrets are injected externally and never written to evidence.
- A run whose seed, evaluator, prompt, model or environment digest differs from its pair is invalid unless the differing field is the registered S/U constraint.

### Authorized account route

- This Pilot is authorized only through `codex login` with ChatGPT plan access. The runner must preflight `codex login status` and require the exact safe status `Logged in using ChatGPT` before every initial candidate run.
- API-key authentication and automatic fallback to API billing are prohibited. A changed or indeterminate login status is an infrastructure stop, not permission to switch credentials.
- ChatGPT authentication confirms the plan-access route; it does not expose the user's remaining quota or prove that a particular plan will never consume optional ChatGPT credits. Those values are not inferred from CLI state.
- The manifest freezes `authentication_mode=chatgpt`, `access_route=chatgpt-plan` and `api_key_fallback_allowed=false`. OpenAI documents ChatGPT sign-in as subscription access and API-key sign-in as usage-based access ([official Codex authentication documentation](https://learn.chatgpt.com/docs/auth)).

## Analysis and decision rule

1. Apply correctness hard gates.
2. Report raw first-pass and final correctness before efficiency.
3. For green runs, compare paired Tokens to Green, time and change amplification for T01/T02/T04; report T03 only as the separate negative-control drift check.
4. Report median and IQR plus every raw run. Do not collapse cold calibration into warmed scored runs.
5. Do not infer jOOQ/PostgreSQL/JPA results from the MP+MySQL Pilot.
6. Prefer a selection rule by entity eligibility over a forced global winner.
7. Only stable repeated evidence may justify an accepted ADR or a production ArchUnit change.

## Decision gates

Step 0 leaves the following decisions for one overall user confirmation before implementation:

1. Freeze persistence-owned immutable Audit, current Actor as audit source, microsecond normalization and returning `save`.
2. Freeze explicit `expectedVersion` on update Commands, canonical uppercase Tenant-local Store code, and the logical DDL above.
3. Approve the initial U rule: framework-free Domain plus Infrastructure-owned direct result mapping; annotation-driven U remains a separate later factor.
4. Confirm Store as a separate Reference Business Service and confirm that it emits no initial integration event without a real consumer.
5. Authorize creation of an immutable neutral Git seed and isolated candidate/evaluator workspaces. The current no-HEAD repository cannot provide reliable diffs without this.
6. Approve the 24 candidate runs, selected model/reasoning effort, maximum two repair turns and cost/stop budget. This approval must be fresh immediately before the Pilot.
7. **Closed 2026-08-30:** the authorized initial+exact-ID-resume probe passed and froze `session-cumulative` usage semantics for `codex-cli 0.144.4`.

Until these gates close, the protocol remains Proposed and no S/U implementation or Pilot may start.
