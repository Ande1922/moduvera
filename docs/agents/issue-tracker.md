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

## System reviews

System audits live under `.scratch/review-<YYYY-MM-DD>-<slug>/`. Follow `review-workflow.md` to publish, verify, plan, and close them. Findings use finding states rather than issue execution states.
