---
name: add-business-service
description: Shape a requested Moduvera Business Service into specification and ticket requirements from its direct, asynchronous, internal, persistence, and App Assembly needs, then route it into the existing delivery stages.
---

# Add Business Service

Use the authoritative
[new Business Service recipe](../../../docs/agents/new-business-service.md) to
shape the capability. The recipe owns architecture branches and completion
criteria; the repository
[delivery workflow](../../../docs/agents/delivery-workflow.md) owns stage
routing and authorization.

## Process

1. Read the request or tracker artifact and the recipe using the
   [task context rules](../../../docs/agents/domain.md#task-context).
   Complete a shape card conforming to the recipe's authoritative
   Shape Contract for every consumer/use-case promise, durable-state need,
   App, and acceptance owner.
   This step is complete when its validation conditions pass and material
   contract, authorization, data, and topology decisions are explicit.
2. Trace every proposed artifact to named consumers, use cases, and support
   promises. Keep only traced artifacts. This step is complete when the plan
   contains no empty module, speculative Adapter, or copied reference-service
   surface and each vertical slice carries its acceptance and test seams.
3. Inspect the current route table under **Route by task shape** in the linked
   delivery workflow. Select the row matching the current artifact state, then
   follow that row's Skill and stopping condition. The workflow table is the
   only stage-key mapping; this Skill does not cache it.
4. Keep intake output at specification and ticket shape. Production modules
   and files are created only after an authorized `implement` or
   `implement-frontier` route owns an approved ticket.

## Completion

Stop when the selected delivery stage reaches its own completion condition.
Return the Shape Contract-conforming inputs, unresolved decisions, selected
workflow row, traced artifacts, vertical specification/ticket shape or paths
produced by that stage, and exact validation evidence. Do not infer
implementation, tracker, commit, integration, push, or deployment authority
from this intake Skill.
