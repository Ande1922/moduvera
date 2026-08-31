---
status: accepted
---

# Use Spring Cloud Stream's functional model with Kafka

Cross-service messaging uses Spring Cloud Stream's imperative `Supplier`/`Function`/`Consumer` programming model with the Kafka Binder. Kafka is the only current broker target; RabbitMQ, RocketMQ, and other binders are deferred until a real requirement exists. Business Commands and Events remain owned by their Business Service APIs, while broker-neutral envelope, Outbox, Inbox, retry classification, ordering, and schema rules remain platform messaging concerns.

Application Services do not depend on Kafka, Binder, or `StreamBridge`. Thin message-entry functions invoke Application Services, and infrastructure Outbox relays use Stream operations after the business transaction commits. Binder transactions do not replace PostgreSQL Outbox/Inbox consistency or consumer idempotency. The first version uses imperative rather than reactive functions so per-message retry and error behavior remain explicit and testable.

This supersedes ADR 0005 while preserving its at-least-once Outbox/Inbox and business-contract decisions.

ADR 0024 keeps this same Kafka model in the modular-monolith topology. A different middleware implementation may be introduced later only behind the messaging boundary and after proving semantic compatibility.

Evidence: explicitly confirmed by the user during the 2026-08-30 architecture simplification review.
