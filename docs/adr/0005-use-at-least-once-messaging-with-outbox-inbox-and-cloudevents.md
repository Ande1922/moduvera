---
status: superseded
superseded-by: 0017
---

# Use at-least-once messaging with Outbox, Inbox, and CloudEvents

Cross-service messages use at-least-once delivery: a producer commits an Outbox Record with business state and a consumer commits an Inbox Record with its business change. Integration Events use structured CloudEvents, Asynchronous Commands remain directed contracts, and Kafka and RocketMQ must pass the same portable contract tests instead of pretending to have identical native features.

This decision is retained as historical evidence. ADR 0017 preserves the Outbox/Inbox and contract semantics, selects Spring Cloud Stream's imperative functional model with the Kafka Binder as the Golden Path, and removes equal multi-broker support from the current target.

Evidence: Q61-Q107; see [the question index](../grill/question-index.md).
