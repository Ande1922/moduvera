# Gateway request diagnostics

The public Gateway creates a new UUID v4 correlation for each HTTP request,
including requests rejected before routing. A caller's correlation or claimed
internal/tenant headers do not select a trusted ingress. The same correlation
is injected into internal requests and returned in `X-Correlation-Id`.
`X-Trace-Id` comes from the actual Agent server context, including valid
unsampled contexts. The Gateway does not create a second server Span or parse
W3C propagation itself.

`GatewayRequestDiagnostics` decorates the complete WebFlux HTTP handler through
Boot's `WebHttpHandlerBuilderCustomizer`. A first WebFilter makes its state
available to exchange attributes and Reactor Context. The completion callback
runs outside exception handling and final response completion; `beforeCommit`
only prepares headers. `GatewayDiagnosticResponse` also observes cancellation
and error on body publishers because a disconnected Netty write can complete
the enclosing handler normally. It forwards buffers without copying, caching,
reading, or rewriting body contents.

One INFO `http.request` event records the observed outcome, status when
committed, monotonic duration, available content lengths, transport peer IP,
and a safely formatted User-Agent when present. Forwarding headers do not
select the peer IP. A committed 200
does not imply success after a failed or cancelled write. Cancellation without
an observed write failure is unknown. The event does not claim that a peer
received all advertised bytes or headers.

Gateway-generated Problems share the request correlation and preserve native
HTTP status and applicable error headers. Unknown exceptions that can still be
handled produce a safe 500 Problem and one `SYS_UNEXPECTED` ERROR. Unexpected
errors after commitment have the same safe final-error owner. The Gateway
closes the underlying Netty response connection without a replacement body or
terminating success chunk; the canonical retains the committed status and failed
outcome. Spring's disconnect classification keeps ordinary peer disconnects
out of intervention-level ERROR output. The direct abort prevents Spring and
Netty from repeating the original exception outside the captured safe scope.
Downstream bodies, including Problems, remain unchanged. A missing or different
downstream response correlation produces `SYS_GATEWAY_CORRELATION_MISMATCH`;
the Gateway keeps its own response headers and does not log the foreign value.
Neither error code includes result or timing fields in its ERROR event.

Identity exchange currently returns an opaque internal credential; it does not
establish a verified local Actor/tenant execution scope in this Gateway.
Diagnostics therefore omit those unknown identity fields. They never decode
credentials or use request headers/Baggage to invent identity. Terminal logging
briefly masks incidental authorization/MDC and opens the diagnostic-only
snapshot, restoring the callback thread's previous state on exit.

## Verification

`./mvnw -pl apps/gateway-app -am verify` runs the focused module checks, real
HTTP routing/rejection/Problem tests, actual client reset, and Reactor
completion/cancellation/scheduler/retry isolation tests. The existing
`GatewayMonolithTargetIT` uses a JDK HTTP target fixture to retain prefix routing;
it does not start or qualify the monolith runtime.

The additional [locked-Agent fixture](../../verification/governed-observability/verify-gateway-fixture.sh)
uses the existing Agent preflight and OTLP receiver. Supply the locked Agent,
extension, external evidence directory, and receiver endpoint as in the
[observability harness](../../verification/governed-observability/README.md).
Run [the analyzer](../../verification/governed-observability/analyze_gateway_fixture.py)
against that evidence directory after the fixture JVM exits. It matches each
canonical to its exported SERVER Span (with one explicit unsampled exception),
checks supplied parents, rejects duplicate completion/extra fixture ERRORs,
and checks the receiver's sensitive-data and signal-export results.
