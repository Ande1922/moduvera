---
status: accepted
---

# Verify infrastructure semantics with runtime Adapters

Persistence, transaction, tenant-isolation, concurrency, and Outbox/Inbox semantics are verified through the production runtime Adapters against real infrastructure, using Testcontainers where an external component is required. Production source sets and published modules do not provide reduced-fidelity `InMemory` implementations solely as test substitutes; a future in-memory runtime Adapter is justified only by a supported runtime use case and must pass the same applicable contract evidence.

Fast tests may define local mocks, scripted stubs, or recording spies in test source when they verify only Domain or Application orchestration and do not emulate or claim equivalence with infrastructure behavior. This refines ADR 0012's layered-testing decision and ADR 0032's Adapter-placement rule: test-only in-memory substitutes do not belong under a production `adapter/outbound` package or in shared production artifacts.
