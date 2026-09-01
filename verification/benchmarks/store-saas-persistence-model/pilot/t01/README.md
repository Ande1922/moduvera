# T01 Unscored Smoke Harness

Status: **Harness and first real unscored S/U smoke pair passed**

This directory prepares one unscored S/U smoke pair before any of the 24 scored
Pilot sessions. It does not create a production implementation or select a
winner.

## Isolation and single-variable boundary

- `seed-template/` freezes the shared Domain, `DefaultStoreApi`, contracts,
  MyBatis-Plus/MySQL dependency versions, public tests and runner hook.
- Candidates may add files only below the two paths in
  `candidate-write-allowlist.txt`; `verify_frozen_seed.py` rejects any changed
  frozen byte, out-of-scope file or symlink.
- `materialize_t01_seed.py` creates byte-identical candidate seeds. The
  separated and unified prompts are written outside the workspace, so the seed
  digest remains identical.
- Candidates implement only `StorePersistenceProvider`. They do not reimplement
  Domain behavior or Application orchestration.
- `evaluator/` and both oracle implementations are evaluator-private and are
  never copied into a candidate workspace.

The final materialization check produced the same seed tree SHA-256 for both
constraints: `baca4e175a35b803c9cc1f64e3c08964b831f789da651501a4c07f71988bc342`.
The two prompt digests differ, as intended.

## Verified evidence

- JDK 26 and Maven Wrapper 3.9.16 compile the frozen seed offline.
- Both evaluator-private S and U oracles compile against the same seed.
- Frozen-byte verification passes for both oracles.
- `T01-S01`, `T01-U01` and independent Tenant/CAS SQL scans pass.
- The original smoke evidence contains 8/8 evaluator-only hidden tests. The
  formal catalog is 19 assertions; `T01-H10` has now been added to the hidden
  suite, so a scored run must produce 9 hidden tests plus H04, S01/U01 and the
  8 public assertions. Both evaluator-private S and U oracles pass the current
  9/9 hidden suite against MySQL 8.4.11. The historical smoke is not
  retroactively upgraded.
- One randomized real Codex pair also passes frozen integrity, the appropriate
  S/U structure rule, the independent SQL scan and both 8-test MySQL suites.
Neither candidate needed a repair turn.

## Single-run preflight runner

`../../scripts/run_one.py prepare` materializes one candidate outside the
repository and writes `manifest.preview.json`, `readiness.json`, the exact
initial/resume argv and a least-privilege permission profile. Prepare and
verify never invoke Codex. A blocked preview is intentionally mutable: only a
future `finalize` transition may create the immutable `manifest.json` used by a
scored run.

The profile inherits `:read-only`, denies command networking, and reopens only
the two candidate source subtrees plus `target/` for writes. Both initial and
exact-ID resume commands use `--ignore-user-config`, `--ignore-rules`,
`--strict-config`, `approval_policy=never`, an empty inherited shell
environment and explicit JDK 26/Maven paths. Resume records and reuses the
candidate working directory because Codex CLI 0.144.4 does not expose `-C` on
`exec resume`.

`../../scripts/probe_permission_profile.py` is a host-side compatibility
diagnostic, not automatically valid calibration evidence. On a host whose
loaded Codex config still contains the legacy `sandbox_mode`, the standalone
`codex sandbox` subcommand follows that legacy mode and cannot prove the
`--ignore-user-config` execution path used by `codex exec`. Such a result must
remain a hard stop rather than being promoted to READY.

The fail-closed preflight also blocks a scored run until evaluator-owned
runtime tracing proves that executed SQL and MyBatis `MappedStatement`/
`ResultMap` types match the statically approved XML. The current semantic XML
and SQL scanners reject common dead-XML, tenant-predicate, CAS and renamed
carrier evasions, but static evidence alone is not promoted to runtime proof.

The durable unscored result is under
`../../evidence/smoke/2026-08-30-t01-chatgpt-plan/`. It validates the path but
does not select an architecture or replace the planned 24-run scored Pilot.
