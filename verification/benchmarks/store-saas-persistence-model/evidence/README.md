# Evidence Layout

Evidence is evaluator-owned and is not mounted into candidate workspaces.

```text
evidence/<experiment-id>/<task-id>/<pair-id>/<variant-label>/r<repeat>/
  manifest.json
  result.json
  agent/
    events.jsonl
    final-message.md
    stderr.log
    usage.json
    commands.jsonl
  source/
    candidate.patch
    file-metrics.json
    structural-scan.json
  tests/
    public-summary.json
    hidden-summary.json
    atomic-assertions.json
    physical-sql.json
    full/
  defects/
    root-causes.json
    repair-briefs.jsonl
  provenance/
    digests.json
    environment.json
    timing.json
```

## Durability and integrity

- `manifest.json` is written and hashed before candidate execution.
- Raw Codex stdout JSONL is immutable input to `usage.json`.
- Multi-turn `usage.json` is not generated until the CLI's per-turn versus session-cumulative semantics have been calibrated and recorded; uncalibrated extraction fails closed.
- Every evidence file recorded in `result.json` has a SHA-256 digest.
- `result.json` must pass both its Draft 2020-12 Schema and `scripts/validate_result_semantics.py` before publication.
- Full logs are retained locally. Standardized summaries are separate derived artifacts.
- Test output distinguishes public, hidden, structural and infrastructure failures.
- Physical-column evidence comes from evaluator SQL that does not call the candidate Mapper.
- A run is invalid when its manifest, seed, prompt, evaluator or environment digest drifts.

## Redaction

Never store credentials, authorization headers, raw environment dumps, database passwords, cookies, request/message bodies unrelated to assertions, or full SQL containing sensitive data. Commands are stored as argv with credential values omitted. Failure summaries contain safe codes and bounded observations.

## Probe exception

The Step 0 Token probes predate the full runner. Their durable directories contain the exact JSON events required to prove usage-field availability and initial+resume session-cumulative semantics, plus summaries of command, warnings and limitations. They are not scored benchmark runs.
