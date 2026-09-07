# Issue tracker: Local Markdown

Issues, specs, and system-review records for this repository live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`.
- The specification is `.scratch/<feature-slug>/spec.md`.
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`.
- For the direct implementation route in [delivery workflow](delivery-workflow.md#route-by-task-shape), the spec itself supplies the acceptance criteria and delivery record; no placeholder issue or map is required.
- Put plain-text `Type: <type>` and `Status: <value>` lines near the top of new tracker files. Select the status from `triage-labels.md` according to the type. Legacy issue files using `**Status:**` do not need migration.
- Comments and conversation history append under a `## Comments` heading.
- New or substantively revised specs and tickets carry `Complexity:` and
  `Complexity basis:` under [task complexity](task-complexity.md). Existing
  history and status-only edits keep their original format.

## Publishing and reading

When a skill says to publish to the issue tracker, create a file under `.scratch/<feature-slug>/`, creating the directory when needed. When a skill says to fetch a ticket, read the referenced Markdown file.

Start discovery from the requested feature's status, map/current frontier, and
remaining nonterminal tickets. Read completed tickets, old review rounds, and
execution logs when a named decision, regression, or qualification question
requires them. A directory's text volume is not a request to load its contents.

Keep an original Problem Statement as dated project background. When a feature
is delivered or substantially superseded in behavior, add a short reading note
linking its closure or remaining tickets and the authoritative
[Product Surface](../implementation/SCAFFOLD-PRODUCT-SURFACE.md). Label execution
records as historical with their actual base/head; historical ownership and
authorization are not current work instructions. Reuse the existing terminal
statuses and closure rules below; an unfinished follow-up keeps its own scope.

## Wayfinding

- A map is `.scratch/<effort>/map.md`.
- Child tickets are `.scratch/<effort>/issues/NN-<slug>.md` and record `Type:` plus `Status:` near the top.
- Blocking uses `Blocked by: NN, NN`; a ticket is unblocked when every listed ticket is `resolved` or `wontfix`.
- Claim a ticket by setting `Status: claimed`; resolve it by appending `## Answer`, setting `Status: resolved`, and linking the decision from the map.

Run `python3 tools/tracker/check.py` after tracker edits. The checker accepts the
legacy bold metadata form, but fails closed on missing or cyclic local blockers,
invalid type/status combinations, stale blocked/unblocked states, non-terminal
children beneath a resolved spec, and missing completion evidence in resolved
issues or resolved specs without children. Completion requires completed
acceptance items, a concrete commit, and affirmative verification evidence in
the Answer. Negative statements such as unavailable, unrun, or failed tests
are not completion evidence, including negated claims that tests cover nothing
or do not cover the change. `Blocked by` entries must fully match `None`, a
two-digit local issue number, or `External — description`; `None` and local
issue numbers may also carry a description after the dash. A `wontfix` issue is
terminal without requiring its blockers to be resolved. A terminal spec may be
`resolved` or `wontfix`. With children, `resolved` requires all children to be
terminal. Without children, a directly implemented spec requires the same
completed acceptance items, concrete commit, and affirmative verification in
its `## Answer` as a resolved issue. `wontfix` may have no children, but any
children it has must all be terminal. The common quality gate extension runs
the same checker for both docs-only and Normal profiles.

## System reviews

System audits live under `.scratch/review-<YYYY-MM-DD>-<slug>/`. Follow `review-workflow.md` to publish, verify, plan, and close them. Findings use finding states rather than issue execution states.
