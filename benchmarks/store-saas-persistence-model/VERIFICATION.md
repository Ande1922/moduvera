# Step 0 Verification

Verified on 2026-08-30. This file separates checks that passed, checks that failed for an environmental reason, and gates that were not available.

## Passed

- Refreshed the current repository code graph in moderate mode: 2,908 nodes and 5,038 edges.
- Fetched the official OpenAI Responses usage schema, Codex non-interactive JSONL example and Codex configuration keys; confirmed the token-detail relationship and the model/reasoning/service-tier override surfaces.
- Ran one local `codex-cli 0.144.4 --json` probe. `turn.completed.usage` contained all four required fields.
- Ran `extract_codex_usage.py` against the raw probe events:

  ```text
  usage_semantics=single
  turns=1
  input=23084
  cached_input=0
  output=5
  reasoning_output=0
  gross_total=23089
  ```

- Verified that uncalibrated two-event extraction exits `2`, while synthetic `per-turn` and `session-cumulative` modes produce the expected distinct totals.
- Ran an authorized non-ephemeral initial turn plus exact-thread-ID resume through the ChatGPT-authenticated CLI. Both expected messages were returned and usage proved session-cumulative (`23652/6` then cumulative `47774/12` input/output); the parser reproduced the correct second-turn delta.
- Ran `validate_result_semantics.py` against a consistent synthetic GREEN result (accepted), a contradictory GREEN result (rejected), and a forged GREEN with a non-zero final public-test exit code (rejected). Pass counts and hard-gate IDs are derived from attempt assertion rows rather than trusted summary fields.
- Parsed every Step 0 `.json` file with the Python standard library after the Schema revisions.
- Checked 34 local Markdown links; all targets exist.
- Completed a final independent read-only review of the frozen Interface, split V1/V2 DDL, task cards, token parser, Schemas and result validator; no Step 0 delivery blocker remained.
- Ran the repository unit-test lifecycle with JDK 26:

  ```bash
  JAVA_HOME=/Users/gaopengcheng/Library/Java/JavaVirtualMachines/openjdk-26-1/Contents/Home \
    ./mvnw -q test
  ```

  Result: exit `0`.

## Earlier Step 0 environmental failure / unavailable gate

- The initial `./mvnw -q verify` reached `DatabaseMigratorIT` but Testcontainers
  could not discover Docker from the sandbox. Host `docker ps` was healthy, so
  this was not evidence of a repository test defect.
- That full repository Testcontainers gate was not rerun. The later approval
  was scoped to the T01 temporary MySQL container and T01 acceptance suites,
  which did pass as recorded below.
- JSON syntax is verified, but a full Draft 2020-12 Schema validator was unavailable: the system and bundled Python lacked `jsonschema`, and no `check-jsonschema`, Ajv or Ajv CLI installation existed. No dependency was installed just to make this gate pass.

## T01 unscored smoke-harness preflight

Verified later on 2026-08-30:

- Added a standalone JDK 26/MyBatis-Plus 3.5.17 T01 seed with frozen Domain,
  Application, contracts, full baseline DDL and public acceptance source.
- Materialized separated and unified workspaces from the same template. Their
  seed tree SHA-256 is identical:
  `baca4e175a35b803c9cc1f64e3c08964b831f789da651501a4c07f71988bc342`.
- Preheated only Maven dependency/plugin metadata; both evaluator-private S and
  U oracles then passed `./mvnw -s .mvn/benchmark-settings.xml -o -q test` with
  JDK 26.
- Both oracles passed frozen-seed integrity verification, their private
  `T01-S01`/`T01-U01` structural assertion, and the independent Tenant/CAS SQL
  scan (`T01-H04`).
- Started an explicitly authorized temporary `mysql:8.4.11` container bound to
  `127.0.0.1:32768`. Both private oracles passed public 8/8 and hidden 8/8 on
  separate blank databases.
- Ran one randomized, ChatGPT-authenticated real Codex pair with
  `gpt-5.6-sol`, high reasoning, default service tier, no API-key fallback and
  fresh initial threads. X was U and Y was S.
- Both candidates passed frozen integrity, their respective T01-U01/T01-S01
  structure rule, T01-H04, public 8/8 and hidden 8/8 under the independent JDK
  26 evaluator. Neither needed a repair turn.
- Captured raw JSONL and calibrated usage. X emitted 3,234,744 input tokens
  (3,128,320 cached) and 17,200 output tokens; Y emitted 1,868,879 input tokens
  (1,795,328 cached) and 13,905 output tokens.
- Removed the temporary MySQL container after evidence capture. Its disposable
  databases are not recoverable; candidate source, raw events and evaluator
  reports remain under `evidence/smoke/2026-08-30-t01-chatgpt-plan/`.

## Not performed by design

- no immutable Git seed, commit, branch or worktree;
- no production S/U implementation;
- no scored Pilot candidate runs;
- no production ADR or architecture-rule change.
