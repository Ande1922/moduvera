---
status: accepted
---

# Retain the modular monolith as an on-demand assembly

The microservice Golden Path is the only default delivery and acceptance
topology. Retain the existing `app-monolith` assembly and build-time composition
model, but withdraw the continuously supported second-topology commitment in
ADR 0022. New features and Business Services need not join or qualify the
monolith unless that work is explicitly in scope.

Keep protocol-neutral Service APIs, service-owned inbound adapters, leaf App
composition roots, and the authorization, tenant, transaction and messaging
contracts used by the current product. Retain basic reactor compilation and
shared architecture checks. Monolith-specific runtime integration, startup,
public black-box, recovery and image runtime qualification are opt-in and do
not block ordinary iteration acceptance.

The retained assembly keeps the ADR 0027 business-core shape, Local synchronous
collaboration and ADR 0024 Kafka Outbox/Inbox semantics. Its source and previous
qualification evidence are reusable assets, not proof that every later version
is runnable or includes every new service. When a concrete monolith use case
arrives, explicitly scope the assembly updates and requalify the target version
before claiming runtime support. This exchanges continuous second-topology
maintenance for possible future adaptation work.

ADR 0001 and ADR 0004 retain their composition and collaboration boundaries;
ADR 0025, ADR 0028 and ADR 0029 retain adapter ownership and route semantics.
Their monolith qualification obligations apply when monolith runtime support
is explicitly requested. Production deployment remains undecided.

Evidence: the user explicitly approved this boundary and its implementation on
2026-09-06. This supersedes ADR 0022's ongoing support and acceptance obligation;
it does not erase historical qualification results.
