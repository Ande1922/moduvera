# System-review workflow

Use this workflow for repository-wide scaffold, architecture, agent-workflow, verification, build, delivery, or documentation audits. Pull-request and diff reviews continue to report separate Standards and Spec axes; they do not use this workflow.

Store an audit under `.scratch/review-<YYYY-MM-DD>-<slug>/`. Its `review.md` is an audit record and index, not a normative capability-status source. `docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md` remains the only current scaffold capability-status source.

## Publish

1. Pin the reviewed commit in `review.md` as `Baseline: <commit>`.
2. Create `review.md` with `Type: review` and `Status: open`.
3. Create one `findings/NN-<slug>.md` file per claim. Start each unverified claim with `Type: finding` and `Status: needs-triage`, and include `Claim:`, `Evidence:`, and `Verification:` fields.
4. Link every finding from the `review.md` index.

A finding without an executable or second-reader `Verification:` path remains `needs-triage`. The publishing session records claims and evidence; an independent session confirms or rejects them. It does not open repair specs or change production code. A claim disproved before publication may start as `rejected` when its file includes `## Verdict` and the disproof.

This step is complete when the baseline resolves, every indexed finding file exists, and every unverified finding has an actionable verification path.

## Verify

1. Use an independent session to read the finding's `Claim:` and `Verification:` before inspecting the implementation.
2. Set the finding to `verifying` while gathering evidence.
3. Confirm a claim only with one of these evidence forms:
   - a new test that fails against the baseline for the claimed reason;
   - a command that reproduces the claim; or
   - a second-reader analysis citing exact code locations.
4. Append `## Verdict` with the evidence, then set the finding to `confirmed`, `rejected`, `needs-info`, or `wontfix`.
5. Set `review.md` to `verifying` while findings are being checked.

This step is complete when every checked finding has a verdict whose evidence can be rerun or reread without relying on the publishing session.

## Plan

1. Group confirmed findings into a repair spec under `.scratch/<feature-slug>/`.
2. Put `Fixes: review-<date>-<slug>/<NN>` near the top of each repair issue.
3. Add `Planned: <path>` to each planned finding. A deliberately deferred finding remains `confirmed`; record its known-debt disposition and reevaluation date in the review index.
4. After a repair lands, add `Fixed: <commit>` and keep the finding `confirmed`.
5. Set `review.md` to `planned` when every confirmed finding has `Planned:` or an explicit known-debt disposition.

This step is complete when every confirmed finding is traceable to a repair plan or a dated known-debt decision.

## Close

Close the review when every finding is one of:

- `rejected`;
- `wontfix`;
- `confirmed` with `Fixed: <commit>`; or
- `confirmed` with an explicit known-debt disposition and reevaluation date.

If the work changes a scaffold capability commitment, update `docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md`. If it changes a hard architectural constraint, add or supersede an ADR. Set `review.md` to `closed` only after those required updates are present.

This step is complete when the index accounts for every finding, all close conditions hold, and normative product or architecture sources reflect any changed commitments.
