---
status: accepted
---

# Business Services own inbound adapters

The providing Business Service owns the protocol-to-use-case semantics of its inbound adapters, including HTTP route and DTO mapping, message kind/type/source/destination validation, payload mapping, and Application use-case invocation. App Assemblies select and activate those adapters together with runtime capabilities, bindings, addresses and security; they do not redefine the business mapping. The same inbound adapter must therefore be reusable by both a separate-process App and the modular-monolith App Assembly.

This keeps App Assemblies as leaf composition roots and prevents topology-specific Controller or Consumer duplication. Platform Web and messaging modules continue to own generic transport mechanics rather than business routes or message contracts. Physical Maven packaging remains a separate decision.

Evidence: explicitly selected by the user during the 2026-08-31 App Assembly review.
