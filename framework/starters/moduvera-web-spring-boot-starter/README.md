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

Embedded Tomcat receives an optional Engine Valve that keeps a projection-only
scope for each synchronous pipeline invocation. It reads the latest immutable
request snapshot, including identity learned after authentication. An active
Span and ExecutionContext take precedence over fallback fields; canonical's
explicit captured snapshot retains precedence over both. The Valve does not
install authorization or OTel state, emit errors, or change responses. Its scope
ends on pipeline return, including while an asynchronous request is waiting.
This projection supports the shipped synchronous Boot ConsoleAppender; deferred
or arbitrary asynchronous appenders are outside this support boundary.

An async dispatch failure still produces both Tomcat's intermediate
ApplicationDispatcher ERROR and its final StandardWrapperValve ERROR. Both now
carry request correlation and trusted identity, with captured server Trace as
fallback when no active Span exists. Tests identify the same request and original
Throwable object; no error is suppressed. This duplicate ownership gap still
prevents full ticket acceptance.

`ServletRequestDiagnosticsIT` runs the real embedded container. The additional
Agent run sets `moduvera.test.agent=true` and uses the repository's locked Agent
and export-sanitization extension to verify actual server Trace relationships.
