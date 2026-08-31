---
status: accepted
---

# Group lightweight platform contracts in one kernel artifact

Framework-free platform-wide primitives for paging, errors, execution context, authorization, and identifier contracts share one `moduvera-kernel` Maven artifact and remain separated by explicit Java packages. Maven artifacts are not created merely to mirror every package. The kernel admits only stable cross-service semantics and does not contain Spring, ORM, Broker, business DTO, persistence Entity, universal Mapper/Repository, or utility-class dependencies.

Business persistence records declare tenant, audit, identifier, and version fields explicitly rather than inheriting a shared `BaseEntity`. Platform messaging remains a separate component because message contracts, Outbox/Inbox reliability, and schema evolution have their own consumers and verification lifecycle. Web and OAuth2 Resource Server also remain separate runtime components rather than being collapsed into the kernel.

The physical consolidation is complete. `moduvera-kernel` now owns the API, error, context, authorization, identifier and framework-free transaction-boundary packages. Package boundaries remain explicit even though Maven no longer mirrors each package.

Evidence: explicitly confirmed by the user during the 2026-08-30 architecture simplification review.
