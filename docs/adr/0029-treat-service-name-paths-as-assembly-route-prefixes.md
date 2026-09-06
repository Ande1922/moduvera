---
status: accepted
---

# Treat service-name paths as assembly route prefixes

Current qualification scope follows [ADR 0038](0038-retain-monolith-as-on-demand-assembly.md).
Monolith-specific runtime evidence is required only when that topology is explicitly in scope.

The external public path remains stable as `/api/{service}/{service-owned-path}`, while each Business Service's public Controller declares only its service-local, major-versioned path such as `/v1/orders`. The `/api/{service}` segment is an External Route Prefix owned by application and edge-route assembly; it is not part of the Service API or duplicated in a Gateway Controller.

For a standalone service behind the Edge Gateway, the Gateway routes by service name and strips `/api/{service}` before forwarding. For an App Assembly that composes multiple Business Services, the App adds the corresponding prefix to each service's public Controllers and the Gateway, when present, forwards the full path without stripping it. Exactly one prefix transformation is active for a route. Internal Controllers are excluded, and route-contract tests must prove that both supported topologies expose the same external paths without duplicate mappings or cross-service collisions.

Evidence: explicitly accepted by the user during the 2026-08-31 App Assembly review; Spring MVC supports predicate-selected Controller prefixes through `PathMatchConfigurer.addPathPrefix`, and Spring Cloud Gateway supports edge stripping through `StripPrefix`.
