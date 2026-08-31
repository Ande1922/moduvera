---
status: accepted
---

# Use Kafka in both application topologies

Both the multi-process Golden Path and the modular-monolith topology use the same Spring Cloud Stream/Kafka asynchronous path, including serialized asynchronous Command and Integration Event contracts, transactional Outbox/Inbox, trusted message context, bounded retry, ordering and recovery semantics. The modular monolith is one executable Business Service composition, not a Broker-free infrastructure bundle; synchronous Service API collaborators may become Local calls, while asynchronous service boundaries continue through Kafka.

This supersedes ADR 0023's proposed Local Message Transport. Avoiding a second transport and reliable-consumption implementation keeps the current boundary explicit and the focused topology inexpensive. A future middleware adapter may replace Kafka only behind the platform messaging boundary and must pass the same semantic contract evidence rather than changing Business Service code.

Evidence: explicitly selected by the user during the 2026-08-31 App Assembly review after considering the Local Transport refactor cost.
