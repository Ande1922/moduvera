---
status: accepted
---

# Make Tenant Context a system boundary

Tenant isolation is a cross-cutting execution contract, not only a SQL filter. A managed business entry establishes a trusted Execution Context for its declared or default scope. Tenant entries and tenant resources require a concrete Tenant scope and fail closed both when context is absent and when an existing context has Platform scope. Platform is an explicit non-tenant execution scope, not a substitute for missing context or a grant of global access. Database-specific mechanisms such as PostgreSQL RLS may add defense in depth but do not define the portable tenant semantics.

The trusted context is explicit at system entry points and infrastructure seams, but routine business interfaces are tenant-transparent. HTTP, message and job adapters establish the applicable context; persistence, cache, lock, Outbox/Inbox and audit implementations consume it and enforce isolation. Existing message contracts remain tenant-only unless a separately designed protocol says otherwise. A Job definition's GLOBAL lock scope describes competition for a lock and does not convert its Execution Context to Platform or grant access. Application services, Domain entities and ordinary Repository methods do not carry `TenantId` merely to implement technical isolation. A business type contains tenant identity only when tenant identity is itself part of a business rule.

Missing context and Platform scope both fail closed at ordinary tenant resources. Cross-tenant administration and tenant fan-out use separate, explicitly authorized interfaces; they must not be implemented with `null`, a magic tenant, Platform scope, or a general-purpose "ignore tenant" switch. PostgreSQL RLS may remain an additional guard, while the portable contract is verified on both PostgreSQL and MySQL through infrastructure-level tenant TCKs.

Evidence: Q8, Q14, Q18, Q21-Q23, Q73, Q103-Q104, refined by the 2026-08-30 architecture review; see [the question index](../grill/question-index.md).
