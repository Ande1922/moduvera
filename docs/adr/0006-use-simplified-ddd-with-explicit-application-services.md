---
status: accepted
---

# Use simplified DDD with explicit Application Services

Services organize code by layer, then business capability, then responsibility; Command and Query Application Services coordinate use cases, Aggregate Repositories belong to the domain, and Query Repositories belong to the application read side. The scaffold does not mandate one Handler per use case, generic Port packages, `ServiceImpl` pairs, or automatic Domain Event collection; domain behavior returns explicit business results that Application Services persist and translate when integration messages are needed.

Evidence: Q45-Q47, Q145-Q160; see [the question index](../grill/question-index.md).
