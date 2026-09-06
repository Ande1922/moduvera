---
status: accepted
---

# Organize Business Services by business module and Adapter direction

Business Service implementation code is organized first by cohesive business module, then by responsibility inside that module. Each business module keeps its protocol-neutral `application` and `domain` code together with `adapter/inbound` and `adapter/outbound`, and exposes one small `XxxModuleConfiguration` at the module root to construct its Application use cases. A module may contain multiple use cases, Application Services, and Application Handlers; a new module is justified by an independent model, language, or reason to change, not by every Handler, entity, or use case.

Inbound HTTP, messaging, and scheduled-entry configuration stays with its driving Adapter. A message entry uses a public `XxxInboundConfiguration` to register a standard Spring `Consumer<Message<byte[]>>` from `ReliableMessageConsumerFactory`. The hidden reliable endpoint owns envelope and contract validation, trusted execution context, bounded retry, and context cleanup without opening a business transaction. The inbound Adapter decodes the provider-owned payload, performs necessary semantic and error mapping, and invokes one independent Application-layer `ApplicationMessageHandler<P>` with that payload and the original `MessageId`.

The Application Handler owns authorization, allowed preparation, domain orchestration, and a fixed `InboxTemplate`. A committed-message precheck precedes protected preparation; mutable consistency reads and all transaction-internal processing run inside the Inbox callback as one complete local transaction. The Handler does not depend directly on top-level transaction control and is not an empty forwarding layer around an Application Service. Command and Event meaning remains in the provider-owned contract rather than duplicate Handler marker interfaces. A separate Mapper exists only when the semantic-difference rule in ADR 0031 justifies it.

Outbound HTTP, messaging, persistence, cache, and in-memory implementations stay with the seam they satisfy under `adapter/outbound`. Inbound and Outbound Adapters collaborate through protocol-neutral Application or Domain seams rather than depending on each other. Business Services do not have generic top-level `configuration` or `infrastructure` catch-all packages; App Assemblies explicitly select business modules and Adapter slices. A service-wide configuration facade is optional only when it imports an inseparable set of business modules, does not activate transport or infrastructure implementations, and is not selected by the current App Assemblies.

This replaces only ADR 0006's layer-first package-ordering rule and refines ADRs 0025 and 0026. Their simplified-DDD, Business-Service ownership, transport-neutrality, and explicit build-time composition decisions remain unchanged.

Evidence: explicitly confirmed by the user during the 2026-09-01 Business Service package-layout review.
