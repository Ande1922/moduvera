# T01 ChatGPT-plan S/U smoke pair

Status: **complete, unscored, both candidates green without repair**

This is the first real Codex CLI smoke pair for T01. Randomization seed
`20260830` assigned X to U (unified mapped Domain entity) and Y to S
(separated flat Row plus explicit object Mapper). Both sessions used a fresh
initial thread, `gpt-5.6-sol`, high reasoning, the default service tier,
`approval_policy=never`, workspace-write sandboxing and ChatGPT login. No API
key fallback was allowed.

## Results

| Candidate | Variant | Frozen | Structure | SQL scope | Public MySQL | Hidden MySQL | Repairs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| X | U | PASS | T01-U01 PASS | T01-H04 PASS | 8/8 | 8/8 | 0 |
| Y | S | PASS | T01-S01 PASS | T01-H04 PASS | 8/8 | 8/8 | 0 |

The independent evaluator used OpenJDK 26 and `mysql:8.4.11`. The database was
bound only to `127.0.0.1:32768`, and public and hidden suites used separate
blank databases for each candidate. The temporary container was removed after
evidence capture.

Token usage is stored exactly as emitted by Codex and interpreted with the
previously calibrated session-cumulative semantics. X used 3,234,744 input
tokens (3,128,320 cached) and 17,200 output tokens. Y used 1,868,879 input
tokens (1,795,328 cached) and 13,905 output tokens. The unexpectedly high
cached-input totals are retained as observed evidence, not normalized away.

## Evidence map

- `pair.json`: pairing, configuration, provenance, usage and gate summary.
- `X/agent` and `Y/agent`: raw initial JSONL, stderr and derived usage.
- `X/evaluator` and `Y/evaluator`: frozen, structure and SQL scans plus public
  and hidden Failsafe reports.
- `X/source` and `Y/source`: the candidate-only Java and XML outputs.

This pair proves that the runner path, both structural constraints and the
database evaluator can execute end to end. It does not choose an architecture:
one unscored pair has no comparative power, and the smoke sandbox is not the
strong isolation boundary required for the 24 scored sessions.
