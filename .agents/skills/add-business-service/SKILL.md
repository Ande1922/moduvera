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

1. Read `AGENTS.md`, `CONTEXT.md`, the request or tracker artifact, and the
   recipe. Complete its shape card: capability, owner, use cases, named
   consumers, direct/message/internal entry, durable state, App Assemblies,
   and verification promises. This step is complete when material contract,
   data, and topology decisions are either explicit or listed as unresolved.
2. Trace every proposed module or file to one named consumer or support
   promise. Keep only traced artifacts and label each one `required by`. This
   step is complete when the plan contains no empty module, speculative
   Adapter, or copied reference-service surface.
3. Select one existing delivery route from the current artifact state:
   `decision-unclear` for material unknowns, `agreed-decision` for a new spec,
   `approved-spec` for ticket splitting, `one-ticket` for one bounded change,
   or `ticket-dag` for dependency-linked implementation. Follow that linked
   Skill and its stopping condition rather than reproducing its process here.
4. Keep intake output at specification and ticket shape. Production modules
   and files are created only after an authorized `implement` or
   `implement-frontier` route owns an approved ticket.

## Completion

Stop when the selected delivery stage reaches its own completion condition.
Return the shape card, unresolved decisions, selected route, traced artifacts,
specification or ticket paths produced by that stage, and its exact validation
evidence. Do not infer implementation, tracker, commit, integration, push, or
deployment authority from this intake Skill.
