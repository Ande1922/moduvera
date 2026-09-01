---
status: accepted
---

# Organize Business Services by business module and Adapter direction

Business Service implementation code is organized first by cohesive business module, then by responsibility inside that module. Each business module keeps its protocol-neutral `application` and `domain` code together with `adapter/inbound` and `adapter/outbound`, and exposes one small `XxxModuleConfiguration` at the module root to construct its Application Services. A module may contain multiple use cases and Application Services; a new module is justified by an independent model, language, or reason to change, not by every Handler, entity, or use case.

Inbound HTTP, messaging, and scheduled-entry configuration stays with its driving Adapter. Message entry uses a public `XxxInboundConfiguration`, a package-private `XxxMessageConsumer`, and a separate Mapper only when mapping is non-trivial. Outbound HTTP, messaging, persistence, cache, and in-memory implementations stay with the seam they satisfy under `adapter/outbound`. Business Services do not have generic top-level `configuration` or `infrastructure` catch-all packages; App Assemblies explicitly select business modules and Adapter slices. A service-wide configuration facade is optional only when it imports an inseparable set of business modules and does not activate transport or infrastructure implementations.

This replaces only ADR 0006's layer-first package-ordering rule and refines ADRs 0025 and 0026. Their simplified-DDD, Business-Service ownership, transport-neutrality, and explicit build-time composition decisions remain unchanged.

Evidence: explicitly confirmed by the user during the 2026-09-01 Business Service package-layout review.
