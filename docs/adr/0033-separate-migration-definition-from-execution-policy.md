---
status: accepted
---

# Separate migration definition from execution policy

Each Business Service owns its database migration resources and exposes a side-effect-free migration definition from a service-level `migration` package. The definition fixes the database component identity, locations, and service-owned metadata, but it does not run migrations or choose environment policy. Persistence Adapter configuration must not execute migrations, and App configuration must not duplicate service migration locations or component metadata.

App Assembly or deployment composition selects which service and platform migration definitions participate and whether they run at application startup or through an external release step. A platform migration runtime supplies the generic executor and combines selected definitions with execution options such as database-identity initialization before constructing executable migration plans. Platform capabilities such as reliable messaging own their own definitions, which an App Assembly selects once even when several Business Services share one process.

This resolves the execution-mechanism decision left open by ADR 0010 while preserving service ownership of migration artifacts and deployment ownership of privileges, timing, and release ordering.

Evidence: explicitly confirmed by the user during the 2026-09-01 Business Service package-layout review.
