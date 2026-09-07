---
name: grill-with-docs
description: Stress-test an unclear Moduvera requirement or design through focused questions and record resolved domain or architecture decisions before specification.
---

# Grill with Docs

Use this stage when product intent, architecture, vocabulary, alternatives, or
test seams are still materially uncertain. Use the
[task context rules](../../../docs/agents/domain.md#task-context) before questioning.

## Process

1. State the decision being tested, known constraints, and the evidence already
   available in the repository.
2. Ask one focused question at a time. Challenge assumptions with repository
   evidence, counterexamples, failure modes, and explicit trade-offs.
3. Keep a decision ledger: accepted choice, rejected alternatives, remaining
   unknowns, and what evidence would resolve each unknown.
4. Identify glossary or ADR changes implied by resolved decisions. Edit them
   only when repository editing was authorized; otherwise provide the exact
   proposed updates.

## Completion

Stop when every material uncertainty is resolved or explicitly deferred with
an owner/evidence need. Return the decision ledger and domain-document changes.
Do not create a spec or tickets unless the user requests the next stage.

Follow the shared [authorization boundary](../../../docs/agents/delivery-standards.md#authorization-boundary).
