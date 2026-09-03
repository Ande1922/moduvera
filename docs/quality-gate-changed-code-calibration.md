# Changed-code calibration

The deterministic cases in
`tools/quality/test/test_changed_code.py::ChangedCodeAnalysisTest::test_four_repeatable_calibration_classes`
exercise the same analyzer invoked by the Normal gate. Each case creates a
clean fixed base/head pair, compiles current Java debug line tables, and creates
a JaCoCo report whose timestamp is inside an explicit successful clean-build
provenance window.

| Representative change | Scoreable result | Expected calibration result |
| --- | --- | --- |
| Domain policy method | 1 changed executable line, 1 method | 100% changed-line coverage; complexity 1; CRAP 1.000 |
| Persistence Adapter method | 1 changed executable line, 1 method | 0% changed-line coverage; complexity 2; CRAP 6.000 |
| Configuration assembly method | 1 changed executable line, 1 method | method line coverage 50%; complexity 2; CRAP 2.500 |
| Documentation and Maven build files | 0 executable lines | coverage and highest CRAP are `n/a`; both paths are explicitly excluded as documentation or build changes |

These values calibrate reporting shape only. They are not release thresholds.
The same suite also proves fail-closed behavior for a missing or stale JaCoCo
report, base/head mismatch, an executable line that cannot be mapped to a
method, and explicit handling of added, modified, deleted, and non-executable
changes.

Run the calibration and its Normal-gate contract with:

```bash
python3 -m unittest -v tools/quality/test/test_changed_code.py
tools/quality/test/run-tests.sh
```

An official Normal invocation saves the bounded calibration-style output in
`summary.txt` and complete metrics in the private `changed-code.json` within
the run selected by `.quality-gate/latest`.
