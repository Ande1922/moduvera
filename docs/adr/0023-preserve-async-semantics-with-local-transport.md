---
status: superseded by ADR-0024
---

# Preserve asynchronous semantics with a Local Message Transport

The modular-monolith topology replaces Kafka transport with a Broker-free Local Message Transport but retains the same serialized asynchronous Command and Integration Event contracts, transactional Outbox, contract validation, trusted Execution Context, Inbox deduplication, consumer transaction, bounded retry, and eventual state transition. A producer transaction never calls the consuming Application Service directly; the Outbox Relay dispatches only after commit, and publication succeeds only after local delivery returns successfully.

The transport-neutral reliable-consumption workflow must sit below Kafka-specific message mapping so both Kafka and Local transports can reuse it. Kafka remains the cross-App Golden Path, while Local transport exists only when producer and consumer Business Services share one App Assembly.

Evidence: explicitly selected by the user during the 2026-08-31 App Assembly review.
