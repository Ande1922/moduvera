---
status: accepted
---

# Make Tenant Context a system boundary

Tenant isolation is a cross-cutting execution contract, not only a SQL filter: every request, message, job, identity, permission check, cache key, and audit record must establish and propagate a trusted Tenant Context and fail closed when it is absent. Database-specific mechanisms such as PostgreSQL RLS may add defense in depth but do not define the portable tenant semantics.

The trusted context is explicit at system entry points and infrastructure seams, but routine business interfaces are tenant-transparent. HTTP filters, message consumers and jobs establish the context; persistence, cache, lock, Outbox/Inbox and audit implementations consume it and enforce isolation. Application services, Domain entities and ordinary Repository methods do not carry `TenantId` merely to implement technical isolation. A business type contains tenant identity only when tenant identity is itself part of a business rule.

Missing context fails closed. Cross-tenant administration and tenant fan-out use separate, explicitly authorized interfaces; they must not be implemented with `null`, a magic tenant, or a general-purpose "ignore tenant" switch. PostgreSQL RLS may remain an additional guard, while the portable contract is verified on both PostgreSQL and MySQL through infrastructure-level tenant TCKs.

Evidence: Q8, Q14, Q18, Q21-Q23, Q73, Q103-Q104, refined by the 2026-08-30 architecture review; see [the question index](../grill/question-index.md).
