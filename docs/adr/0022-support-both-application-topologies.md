---
status: accepted
---

# Support both microservice and modular-monolith application topologies

The scaffold supports two build-time application topologies over the same Business Service and Service API code. The multi-process microservice topology remains the Golden Path and owns the full remote-HTTP, Kafka, and failure-recovery reference path; the modular monolith is also a product commitment and must pass focused end-to-end acceptance proving one executable entry point, Local collaborator selection, unchanged use-case authorization and transaction semantics, and the complete reference-business flow. Topology support does not create a Cartesian product of database and Broker matrices, and runtime topology switching remains forbidden.

ADR 0027 scopes the first focused modular monolith to the Catalog, Order and Inventory business core behind separate Gateway and Identity App Assemblies.

This supersedes ADR 0015's decision to leave the modular-monolith App Assembly optional. The current `app-monolith` shell is not supported until executable evidence satisfies the focused topology boundary.

Evidence: explicitly selected by the user during the 2026-08-31 App Assembly review.
