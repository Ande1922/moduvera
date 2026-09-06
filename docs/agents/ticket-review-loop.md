# Ticket review loop

Use this protocol inside an authorized `implement-frontier` run. It gives the
original worker and two independent reviewers responsibility for bounded
repairs. The coordinator retains scope, exceptions, cross-ticket judgment,
and final acceptance. Standalone implementation or review keeps its own stop
condition.

## Register once

At the first review-ready commit, the coordinator records in the existing
ledger/state: ticket, base, worktree/branch, original worker and Standards/Spec
agent IDs, separate external report locations by round and axis, authorized
operations, acceptance criteria, required verification, and high-risk seams
requiring coordinator attention. Register both reviewers before enabling peer
continuation. Keep source writes with the original worker and each review
report with its author.

Grant routine repair authority only for findings that restore an existing
criterion or repository rule within the assigned ticket and authorized edit
scope. The worker must agree with the finding. Changes to public contracts,
security/trust boundaries, migration strategy, cross-ticket ownership, runtime
dependencies, ticket dependency edges, or required verification obligations
return to the coordinator. For high-risk repairs, the coordinator must explicitly
preauthorize a bounded repair scope and verification at registration; otherwise
escalate before editing, including newly discovered high-risk changes. That
preauthorization preserves the later coordinator risk inspection and does not
need to be repeated for each repair inside the same approved boundary.
Changing or waiving a user requirement needs the user's decision.

## Review and repair

1. The worker stops source writes at a committed head and runs the shared
   [preflight](delivery-standards.md#review-and-gate-evidence) in current-clean
   mode. Send both reviewers the same base/head, receipt, criterion evidence,
   and report destinations. Reviewers keep separate contexts and conclusions.
2. Each reviewer saves its original report for that pair, including finding
   IDs, severity, requirement/rule evidence, and closure conditions; notify the
   worker directly. A report may be clean, contain findings, or be blocked.
   The worker collects both reports before changing source again. If one is
   pending, return waiting; its completion notification resumes the worker.
3. The worker fixes eligible, undisputed findings together in the same
   worktree. Run affected regressions and cleanup, create an ordinary follow-up
   commit when authorized, then send both reviewers the new preflight receipt,
   repair delta, affected criteria/checks, and retained evidence with its actual
   source revision. Store each round separately; preserve earlier reports.
4. Both original reviewers assess the delta, prior finding closure, and impact
   on previously accepted conclusions. Reuse unchanged evidence only after
   confirming its scope still applies; expand review when dependencies or
   behavior require it. Each axis issues a result naming the complete current
   base/head. Only the authoring reviewer closes its findings; the worker
   cannot approve its own change or suppress another axis's report.
5. When both axes are complete and clean for the same current pair, the worker
   sends the coordinator one review-complete packet: ticket/base/head,
   preflight receipt, criterion-to-evidence mapping, and each axis's current
   report path/status/reviewed base/head. Link earlier rounds as finding history;
   they cannot substitute for current-head results. Include remaining risks.
   The coordinator performs the registered risk checks and cross-ticket
   assessment before authorized integration. Aggregate review,
   the repository gate, applicable Scenarios, and final acceptance still apply.

## Escalation and transport

Send the coordinator the unresolved decision and original evidence when a
finding is disputed, a registered boundary changes, evidence is contradictory,
ownership or comparison no longer matches, or a reviewer is unavailable. If a
reviewer rejects a submitted repair because the same confirmed cause remains,
the coordinator decides the next repair plan from the cause, affected entry
paths, failed closure evidence, and proposed plan. Ordinary local test/debug
iterations stay with the worker; round count alone does not force replanning.
Keep the current state and stop dependent edits while the
decision is pending. Reviewers report such exceptions directly as well as to
the worker; the worker cannot filter them from the coordinator.

Use peer `followup_task` only for the registered ticket/role/worktree, preserving
model and effort. `send_message` delivers information but does not wake an idle
agent. Publish a complete report or repair packet before continuing its peer;
do not send a separate wake-up per finding. If peer continuation is unavailable,
ask the coordinator to relay that exact packet without repeating the technical
review. Source writes remain paused until both reports for the same pair arrive.
Keep routine completion messages to ticket/head/status/report path; substantive
findings remain in the original reports. Runtime notifications reaching the
coordinator do not require it to re-evaluate each routine repair.

Keep the coordinator informed at first review-ready, review-complete, and
escalation boundaries. Required progress updates can summarize existing state;
they do not require another evidence audit. Keep raw logs in the existing
evidence store and link them from the result index rather than copying them
through worker, coordinator, and final reports. Model settings remain unchanged
for the initial workflow trial; measure whole-run cost and defects as well as
coordinator requests before claiming an improvement.
