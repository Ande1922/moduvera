# Task complexity

Classify a spec's full scope, each ticket's own slice, and an implementation
request without a tracker. The label determines the required explanation and
evidence. File count, expected duration, model choice, and Story count do not
determine complexity.

## Classification

Rate each axis below by checking **high first, then medium**. Choose low only
when its conditions hold. The task's level is the highest of the three ratings;
a lower rating on another axis cannot offset it. Judge the behavior being
changed, so a formatting edit in a security module is still a formatting edit.

| Axis | low | medium | high |
| --- | --- | --- | --- |
| Scope | One established seam with unchanged contracts, or a mechanical/documentation correction with unchanged meaning. | New or changed behavior within one capability using established contracts; shared development rules or build configuration without changing production contracts. | Coordinated contract changes across independently owned capabilities; data/protocol compatibility or migration; changed authentication, authorization, tenant isolation, transaction, or message-correctness invariants. |
| Decisions | Outcome, governing constraints, and expected results are known from the request or accepted decisions. | Local implementation choices remain within an established pattern; observable behavior is agreed. | An outcome, public contract, data/trust boundary, or cross-capability decision is unresolved, or a feasibility experiment is needed to choose the approach. |
| Verification | Existing deterministic checks or focused tests provide a direct expected result. | Infrastructure, an independent consumer, or behavioral evaluation is needed using an established test approach. | Correctness needs controlled concurrency, cancellation/retry/recovery, migration/rollout qualification, a performance baseline, or a new external verification setup. |

A capability is an ownership boundary such as a Business Service or framework
capability, not an individual file or Maven module. Cite the request, affected
seam, decision, or test obligation behind each rating. An unknown that prevents
rating an axis makes Decisions high until resolved; state the missing fact.

## Annotation

Add these two lines near the metadata of newly created or substantively revised
specs and tickets. For implementation without such an artifact, put them in the
scope/result summary; create no tracker solely to hold the label.

```text
Complexity: medium
Complexity basis: scope=medium (shared spec/ticket rules); decisions=low (agreed behavior); verification=medium (existing checks and independent task cases).
```

For an existing ticket without a label, classify it in the implementation
summary. Status changes, link repairs, and historical reading notes alone do
not require retroactive annotation. Reassess when scope, decisions, or required
verification changes. A ticket may rate below its parent only when its own
slice excludes the parent's higher trigger and preserves all assigned criteria.

## Required detail

Every level needs the outcome, scope, numbered acceptance criteria with expected
results and verification seams, governing decisions, and exclusions. Reuse
authoritative contracts by reference.

| Level | Additional detail required |
| --- | --- |
| low | A compact statement of the changed behavior and its regression/check is sufficient. |
| medium | Identify changed seams, dependencies, and applicable failure cases; connect each to acceptance evidence. |
| high | State the affected invariants, decision alternatives and unresolved questions, plus applicable concurrency, recovery, compatibility, or rollout scenarios and their evidence. |

Use User Stories when a role and goal add information beyond those criteria.
Their number follows distinct needs; no level has a Story quota. A technical
spec may express all behavior directly as acceptance criteria.

Review the annotation against the scope and test obligations before treating
the spec or ticket as ready. Unresolved material decisions follow the existing
[delivery workflow](delivery-workflow.md); a high label alone does not require
a new stage, delegation, or a frontier. Every level retains the repository's
review, test, and authorization obligations.

Examples: correcting a stale link is low; adding behavior within one capability
with an established PostgreSQL test seam is medium; repairing tenant isolation
in one file is high; establishing an Outbox performance baseline is high.
