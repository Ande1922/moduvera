---
name: final-acceptance
description: Audit current review, gate, ticket, and applicable Scenario evidence and issue a truthful final delivery result without inferring release authority.
---

# Final Acceptance

Use the checklist in the authoritative
[delivery workflow](../../../docs/agents/delivery-workflow.md#final-acceptance-boundary)
and the evidence invariants in
[delivery standards](../../../docs/agents/delivery-standards.md#review-and-gate-evidence).

Resolve the delivered base/head, require both review axes to be completed
against that exact comparison, verify the recorded gate evidence belongs to
the same base/head and still exists, account for every ticket criterion and
required check, determine applicable Scenario gates from the changed
contracts, and confirm the checkout is clean.

Start from the existing result index and original receipts/review reports.
Use deterministic checks for comparison and execution facts, then judge whether
the criterion evidence and cross-ticket behavior support acceptance. Inspect
identified high-risk seams and drill into missing or contradictory evidence;
routine success does not require repeating the reviewers' complete code review.
A ticket review-complete result alone cannot satisfy this stage.

## Completion

Return PASS only when every required item is current and successful. Otherwise
return FAIL or BLOCKED with the exact missing evidence or authority. Include
base/head, review status, gate evidence path, Scenario results, and remaining
risks. Final acceptance is read-only unless the user separately authorizes a
specific commit, tracker, integration, push, cleanup, publish, or deploy action.
