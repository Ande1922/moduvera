# Tracker statuses

The allowed value of a tracker's `Status:` field is selected by its `Type:` field. For legacy files without `Type:`, infer the type from the path: `spec.md` is a spec, `issues/*.md` is an issue, `findings/*.md` is a finding, and `review.md` is a review.

## Intake

Use these states for specs and issues before implementation starts.

| Tracker status | Meaning |
| --- | --- |
| `needs-triage` | Maintainer evaluation is required |
| `needs-info` | Reporter information is required |
| `ready-for-agent` | The work is fully specified and ready for an agent |
| `ready-for-human` | Human implementation is required |
| `wontfix` | The work will not be actioned |

When an engineering skill names a canonical intake role, use only this table.

## Issue execution

Use these execution states for issues. A spec may use `resolved` after every
child issue is `resolved` or `wontfix`, or after direct implementation without
children satisfies the [tracker closure evidence](issue-tracker.md#wayfinding).

| Tracker status | Meaning |
| --- | --- |
| `claimed` | An agent or human owns the issue and is implementing it |
| `blocked` | A stated dependency or required decision prevents progress |
| `resolved` | The implementation and required evidence are complete |

## Finding

Use these states only for system-review findings.

| Tracker status | Meaning |
| --- | --- |
| `needs-triage` | The claim has a verification path but has not been independently checked |
| `needs-info` | Required evidence or context is unavailable |
| `verifying` | An independent reviewer is executing the verification path |
| `confirmed` | Independent evidence supports the claim |
| `rejected` | Independent evidence disproves the claim |
| `wontfix` | The claim is accepted but will not be actioned |

A confirmed finding remains `confirmed` after remediation. Record remediation with `Fixed: <commit>` and its plan with `Planned: <path>`.

## Review

Use these states only for `review.md`.

| Tracker status | Meaning |
| --- | --- |
| `open` | Findings are published and await independent verification |
| `verifying` | At least one finding is under independent verification |
| `planned` | Every confirmed finding has a repair plan or an explicit known-debt disposition |
| `closed` | Every finding has reached an allowed close condition |
