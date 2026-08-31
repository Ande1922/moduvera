---
status: accepted
---

# Make publication guarantees explicit

Callers choose a publication guarantee through one of two sibling capabilities. `DurablePublication.append` enlists an intent in the caller's active, writable local database transaction; returning means only that the intent joined that transaction, not that Kafka acknowledged it. `ImmediatePublication.publish` is a one-shot synchronous send that returns only after the configured Broker acknowledgement; it has no Outbox persistence, retry, cleanup, or redrive, and a timeout can leave the Broker outcome unknown.

The two interfaces have no common publication parent, mode argument, qualifier-based semantic switch, or configuration property that can turn one guarantee into the other. A service makes its choice explicitly through the constructor type of its purpose-named publisher adapter. The Kafka starter may provide both capabilities at the same time.

Durable Publication fails before insertion unless a writable transaction is active and bound to the Outbox `DataSource`. Immediate Publication fails before sending whenever a database transaction is active. Durable Publication composes the JDBC intent writer; the relay-facing `OutboxStore` does not implement it and exposes no append operation.

`OutboxStore`, `OutboxAdministration`, and `MessageTransport` are implementation seams for the Outbox relay and Kafka adapter. Business modules depend only on the nominal publication capabilities, and an architecture test prevents them from depending on the Outbox internal package. This decision deepens ADR 0019 without changing its Broker-acceptance boundary.
