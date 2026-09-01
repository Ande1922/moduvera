# Store SaaS Persistence-Model Benchmark

Status: **Proposed — Step 1 T01 unscored smoke pair complete**

This benchmark compares two persistence-model strategies against the same Store SaaS business and the same observable contracts:

- **S — Separated Persistence Model**: framework-free Domain entities plus Infrastructure-owned flat Row/DO objects and explicit object mapping.
- **U — Unified Mapped Entity**: qualified simple Domain entities are mapped directly by the Infrastructure Adapter, with no second persistence object or Domain-to-Row object Mapper.

The experiment does not compare MyBatis-Plus with JPA or jOOQ. The first Pilot fixes MyBatis-Plus and MySQL so that persistence-model shape is the only intended variable.

## Step 0 deliverables

- [PROTOCOL.md](./PROTOCOL.md) — hypotheses, shared Interface, fairness, scoring, evaluator isolation and stop conditions.
- [contracts/SHARED-INTERFACE.md](./contracts/SHARED-INTERFACE.md) — binding candidate-visible Service, Repository, Inventory, Inbox and Outbox seams.
- [ddl/mysql-v1-baseline.sql](./ddl/mysql-v1-baseline.sql) and [ddl/mysql-v2-store-operating-time.sql](./ddl/mysql-v2-store-operating-time.sql) — separately checksummed baseline and fixed-change migrations.
- [tasks/](./tasks/) — four frozen task cards.
- [schemas/run-manifest.schema.json](./schemas/run-manifest.schema.json) — pre-run configuration and provenance.
- [schemas/run-result.schema.json](./schemas/run-result.schema.json) — correctness, usage, defect and change-cost results.
- [templates/run-manifest.example.json](./templates/run-manifest.example.json) — blocked example; it is not evidence of a completed run.
- [scripts/extract_codex_usage.py](./scripts/extract_codex_usage.py) — strict parser that fails closed on uncalibrated multi-turn usage.
- [scripts/validate_result_semantics.py](./scripts/validate_result_semantics.py) — arithmetic and cross-field result validation not expressible in JSON Schema.
- [evidence/README.md](./evidence/README.md) — durable evidence layout and redaction rules.
- [evidence/probes/2026-08-30-codex-cli-usage/](./evidence/probes/2026-08-30-codex-cli-usage/) — the Step 0 Token-usage probe.
- [evidence/probes/2026-08-30-codex-cli-resume-usage/](./evidence/probes/2026-08-30-codex-cli-resume-usage/) — authorized initial+resume calibration proving session-cumulative usage.
- [VERIFICATION.md](./VERIFICATION.md) — checks actually run and unavailable gates.
- [pilot/t01/](./pilot/t01/) — neutral T01 seed, external prompts, evaluator-only
  checks and private S/U feasibility oracles for the first unscored smoke pair.
- [evidence/smoke/2026-08-30-t01-chatgpt-plan/](./evidence/smoke/2026-08-30-t01-chatgpt-plan/) —
  first real ChatGPT-authenticated Codex S/U smoke pair and independent JDK 26 / MySQL evidence.

## Current stop point

Step 0 and one real unscored T01 S/U smoke pair now exist. They do **not**:

- create or accept a production ADR;
- implement S or U;
- create a Git commit, branch or worktree;
- freeze an immutable Git seed or establish scored-run isolation;
- run the 24-run Pilot;
- change the accepted framework-free Domain architecture rule.

The T01 seed is byte-identical across S/U. Both private oracles and the first
randomized real Codex pair pass their structure rules, independent Tenant/CAS
SQL scans, and public/hidden MySQL suites. This single unscored pair is runner
evidence, not comparative evidence. The scored Pilot remains blocked until the
decisions listed in [PROTOCOL.md](./PROTOCOL.md#decision-gates) are confirmed
and an immutable neutral seed is authorized.
