---
status: accepted
---

# Deliver a versioned golden path with build-time application topology

The scaffold is an opinionated, versioned product made of reusable components plus a runnable reference application. The same business code supports a modular monolith and multiple application processes by build-time assembly rather than runtime topology switching. This decides code composition, not how those applications are deployed.

Delivery priority follows [ADR 0038](0038-retain-monolith-as-on-demand-assembly.md): microservices own default
acceptance; the monolith and its runtime qualification are retained on demand.

Evidence: Q1-Q6 and Q4 in particular; see [the question index](../grill/question-index.md).
