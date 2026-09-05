# HTTP Execution Boundaries

The Resource Server establishes an `ExecutionContext` only for MVC handlers
that an App explicitly selects. This keeps handler ownership separate from the
security policy: Spring Security still authenticates and authorizes every
request according to the App's filter chain, whether a handler is managed,
excluded or unselected.

## Select handlers in the App

Publish one `ExecutionContextHandlerSelection` bean from the App assembly. A
selection may use concrete handler types, packages, or both. Exclusions win
when a type matches both lists and should name non-business surfaces that live
inside an otherwise managed package.

```java
@Bean
ExecutionContextHandlerSelection httpExecutionHandlers() {
    return ExecutionContextHandlerSelection.builder()
            .managePackage("com.example.orders.adapter.inbound.http")
            .excludeHandlers(BuildInfoController.class)
            .excludePackage("org.springframework.boot.actuate")
            .build();
}
```

Without this bean, the Resource Server continues to authenticate and authorize
requests but does not install an HTTP `ExecutionContext`. `permitAll`, a JWT,
a tenant claim and a `Tenant-Id` header never select a handler implicitly.

## Declare the handler scope

A selected handler defaults to Tenant. `@ExecutionBoundary` can declare
Platform or Tenant on a class or method; a method declaration overrides its
class.

```java
@RestController
@ExecutionBoundary(ExecutionBoundaryMode.PLATFORM)
class PlatformOperationsController {

    @GetMapping("/platform/status")
    Status status() {
        return operations.status();
    }

    @PostMapping("/platform/tenants/{tenantId}/rebuild")
    @ExecutionBoundary(ExecutionBoundaryMode.TENANT)
    void rebuild(@PathVariable String tenantId) {
        operations.rebuild();
    }
}
```

The annotation declares the execution scope; it grants no permission. Keep
request authorization in Spring Security and use-case authorization at the
application boundary.

## Authentication and tenant rules

Authentication first maps a verified JWT to a trusted Actor, Initiator,
permissions and an optional tenant assertion. Handler resolution then selects
the entry mode and the interceptor installs the context immediately before the
Controller runs.

The default correlation resolver establishes the first valid
`X-Correlation-Id` or generated ID in the shared Servlet request attribute. The
Controller context, later security Problem rendering, and selected redispatches
therefore reuse one value even when the Resource Server is used without the Web
Starter correlation filter. An App-provided `RequestCorrelationIdResolver` remains
the supported customization seam and owns equivalent request-stability semantics.

- A Platform handler creates Platform scope. A client `Tenant-Id` does not
  change it and is not a tenant-switch protocol.
- A Tenant handler requires a concrete trusted tenant. A USER uses its
  `tenant_id` assertion, and any supplied `Tenant-Id` must match it.
- A USER without `tenant_id` remains a valid authenticated identity for a
  Platform handler and receives 403 at a Tenant handler.
- A SERVICE request to a Tenant handler must provide `Tenant-Id`; when the JWT
  also asserts a tenant, the values must match. Selecting a tenant grants no
  permission.
- Missing or invalid authentication remains 401. An authenticated identity
  that cannot satisfy a Tenant boundary receives 403. Both use the existing
  Resource Server Problem response.

Excluded and unselected handlers receive no context from this adapter. They
remain subject to their security configuration and must establish a context
through another trusted entry adapter if their work requires one.

## Servlet lifecycle

The adapter opens a fresh Scope in MVC `preHandle`, after the actual
`HandlerMethod` is known. It closes the Scope on the same dispatch thread:

- normal or exceptional synchronous completion closes in `afterCompletion`;
- an asynchronous handoff closes in `afterConcurrentHandlingStarted`;
- a later ASYNC redispatch resolves its handler and opens another Scope;
- an ERROR redispatch receives a Scope only when its error handler is selected.

The Scope is never saved for another thread to close. This first phase does
not transparently propagate context into the body of MVC `Callable`,
`DeferredResult` producers, reactive return values, or arbitrary callbacks.
Those bodies need their own supported propagation boundary when they perform
context-dependent business work.
