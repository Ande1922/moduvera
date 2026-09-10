# Servlet request diagnostics

The starter establishes one request attribute before Spring Security and keeps
its correlation, monotonic start time, server OpenTelemetry Context, immutable
trusted log fields and terminal marker through dispatches. The container request
listener closes synchronous/error requests; async completion uses the same marker.
Timeout/error notifications record facts without prematurely emitting a result.
No request or response body is buffered for logging.

Public ingress is the default and generates a new UUID v4 even for queries.
A protected assembly may set `moduvera.web.internal-ingress=true` to inherit only
`[A-Za-z0-9][A-Za-z0-9._-]{0,127}` correlation values; missing or invalid internal
values generate one replacement and one WARN. No request header can enable this
mode. The reference harness binds Catalog, Order and Inventory to loopback when
it enables this mode. Production assemblies must supply their own network/route
protection; this diagnostic setting does not authenticate traffic.

The Agent owns W3C propagation. Writable responses receive `X-Correlation-Id` and,
when an actual Agent context exists, `X-Trace-Id`. The starter never invents a
Trace, emits `traceparent`, or changes authorization context. Resource Server
saves trusted fields after establishing its execution boundary; terminal logging
opens only a diagnostic scope, never a restored authorization scope.

Canonical `http.request` remains INFO for every outcome. Observed transport
failure is failure with no claimed delivered status or response length. A
recovered timeout uses the final response. Servlet failures that escape remain
owned by the existing container error handler; the listener does not duplicate
its final ERROR. The Servlet API cannot prove that a peer received bytes when no
transport failure was observed.

One residual gap remains: Tomcat's own ERROR for exceptions escaping the filter
chain runs outside the diagnostic scope and therefore lacks `correlation_id`.
Its stable error code and sanitized cause remain available, and the separate
canonical carries the request correlation. Fixing that final-handler projection
requires a container-boundary decision; the current implementation does not
claim full ticket acceptance.

`ServletRequestDiagnosticsIT` runs the real embedded container. The additional
Agent run sets `moduvera.test.agent=true` and uses the repository's locked Agent
and export-sanitization extension to verify actual server Trace relationships.
