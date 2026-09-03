# Issue tracker: Local Markdown

Issues, specs, and system-review records for this repository live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`.
- The specification is `.scratch/<feature-slug>/spec.md`.
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`.
- Put plain-text `Type: <type>` and `Status: <value>` lines near the top of new tracker files. Select the status from `triage-labels.md` according to the type. Legacy issue files using `**Status:**` do not need migration.
- Comments and conversation history append under a `## Comments` heading.

## Publishing and reading

When a skill says to publish to the issue tracker, create a file under `.scratch/<feature-slug>/`, creating the directory when needed. When a skill says to fetch a ticket, read the referenced Markdown file.

## Wayfinding

- A map is `.scratch/<effort>/map.md`.
- Child tickets are `.scratch/<effort>/issues/NN-<slug>.md` and record `Type:` plus `Status:` near the top.
- Blocking uses `Blocked by: NN, NN`; a ticket is unblocked when every listed ticket is `resolved` or `wontfix`.
- Claim a ticket by setting `Status: claimed`; resolve it by appending `## Answer`, setting `Status: resolved`, and linking the decision from the map.

Run `python3 tools/tracker/check.py` after tracker edits. The checker accepts the
legacy bold metadata form, but fails closed on missing or cyclic local blockers,
invalid type/status combinations, stale blocked/unblocked states, non-terminal
children beneath a resolved spec, and resolved issues without completed
acceptance items, a concrete commit, and affirmative verification evidence in
their Answer. Negative statements such as unavailable, unrun, or failed tests
are not completion evidence. `Blocked by` entries must fully match `None`, a
two-digit local issue number, or `External — description`; `None` and local
issue numbers may also carry a description after the dash. A `wontfix` issue or
spec is terminal without requiring its blockers to be resolved. The common
quality gate extension runs the same checker for both docs-only and Normal
profiles.

## System reviews

System audits live under `.scratch/review-<YYYY-MM-DD>-<slug>/`. Follow `review-workflow.md` to publish, verify, plan, and close them. Findings use finding states rather than issue execution states.
