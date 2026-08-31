---
status: accepted
---

# Deliver a versioned golden path with build-time application topology

The scaffold is an opinionated, versioned product made of reusable components plus a runnable reference application. The same business code supports a modular monolith and multiple application processes by build-time assembly rather than runtime topology switching. This decides code composition, not how those applications are deployed.

ADR 0022 refines delivery priority: the multi-process microservice topology remains the Golden Path, while the modular monolith is a second supported topology with a focused, non-combinatorial acceptance boundary.

Evidence: Q1-Q6 and Q4 in particular; see [the question index](../grill/question-index.md).
