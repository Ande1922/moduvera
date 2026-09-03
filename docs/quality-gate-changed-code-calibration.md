# Changed-code calibration

Two calibration layers are deterministic. The small analyzer cases in
`ChangedCodeAnalysisTest.test_four_repeatable_calibration_classes` pin exact
counter arithmetic. `RealMavenNormalCalibrationTest` creates committed
base/head pairs, runs the repository Normal orchestrator and its exact Maven
clean verify, produces real JaCoCo XML and class debug tables, invokes the
committed changed-code extension, and asserts each private bounded summary.

| Representative change | Scoreable result | Expected calibration result |
| --- | --- | --- |
| Domain policy method | 1 changed executable line, 1 method | 100% changed-line coverage; complexity 1; CRAP 1.000 |
| Persistence Adapter method | 1 changed executable line, 1 method | 0% changed-line coverage; complexity 2; CRAP 6.000 |
| Configuration assembly method | 1 changed executable line, 1 method | method line coverage 50%; complexity 2; CRAP 2.500 |
| Documentation and Maven build files | 0 executable lines | coverage and highest CRAP are `n/a`; both paths are explicitly excluded as documentation or build changes |

The real Normal fixture records and asserts these bounded summaries:

| Normal change | Observed bounded summary |
| --- | --- |
| Domain constructor, overloads, and lambda | 4 changed executable lines, 5 changed methods; 100.00% (4/4); highest CRAP 1.000 |
| Persistence Adapter | 1 changed executable line, 1 changed method; 100.00% (1/1); highest CRAP 1.000 |
| Configuration assembly | 1 changed executable line, 1 changed method; 100.00% (1/1); highest CRAP 1.000 |
| Documentation | 0 changed executable lines and methods; coverage/CRAP `n/a`; one documented build/docs exclusion |

These values calibrate reporting shape only. They are not release thresholds.
The real domain case additionally maps a constructor, overloaded descriptors,
and a compiler-generated lambda method. The same suite proves fail-closed
behavior for a missing or stale JaCoCo
report, base/head mismatch, an executable line that cannot be mapped to a
method, a post-Maven report rewrite with restored mtime, inode replacement,
symlink substitution, and explicit handling of added, modified, deleted, and
non-executable changes.

Run the calibration and its Normal-gate contract with:

```bash
python3 -m unittest -v tools/quality/test/test_changed_code.py
tools/quality/test/run-tests.sh
```

An official Normal invocation saves the bounded calibration-style output in
`summary.txt` and complete metrics in the private `changed-code.json` within
the run selected by `.quality-gate/latest`.
