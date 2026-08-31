---
status: accepted
---

# Use one Service API for local and remote calls

Each Business Service publishes a protocol-neutral Service API; its Application Service is the local implementation and a client adapter is the remote HTTP implementation. App assembly selects one implementation through build dependencies, so callers do not encode process topology and both paths preserve authorization, error, and transaction-boundary semantics.

ADR 0022 refines delivery priority: the remote HTTP path is required by the multi-process Golden Path and the Local implementation is required by the focused modular-monolith topology. This does not multiply every infrastructure acceptance scenario.

Evidence: Q24, Q27-Q29, Q40-Q43, Q115, Q129; see [the question index](../grill/question-index.md).
