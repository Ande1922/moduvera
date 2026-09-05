---
status: accepted
---

# Expose authentication and authorization seams without owning IAM

The scaffold owns the trusted authentication mapping, Execution Context, Actor, Initiator and Platform/Tenant execution-scope propagation, provider-owned Permission Code, fail-closed use-case authorization interface, stable denial/unavailable semantics, and Adapter verification contracts. Platform and Tenant are execution scopes, not roles or permission levels. Platform does not imply platform administrator status or cross-tenant access, and installing a target scope does not authorize that switch. The scaffold does not own a user directory, credential lifecycle, Tenant registry, dealer or organization hierarchy, RBAC tables, role or resource administration, Resource Manifest, License model, temporary authorization workflow, or object-level data authorization; consumer or external implementations map those models into the scaffold seams.

The claims-based authorizer and the opaque-session-to-internal-JWT `identity-app` remain default or reference Adapters that prove the seams, not mandatory IAM architecture. A concrete authorization Adapter may use token permissions, local RBAC, a remote IAM system, or a combined RBAC-and-License decision without changing Application Services, which continue to request only stable Permission Codes at use-case boundaries.

This supersedes ADR 0002 and replaces the uncommitted minimal-IAM, Tenant-directory, access-resource and scenario-resource decisions explored earlier in the same review.

Evidence: explicitly confirmed by the user during the 2026-09-01 IAM resource-authorization and License-boundary review.
