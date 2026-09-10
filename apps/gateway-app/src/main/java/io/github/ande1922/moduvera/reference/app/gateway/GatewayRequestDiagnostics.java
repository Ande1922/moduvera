package io.github.ande1922.moduvera.reference.app.gateway;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** One public request, including pre-route errors and the final response write. */
final class GatewayRequestDiagnostics {

    static final String HEADER = "X-Correlation-Id";
    static final String TRACE_HEADER = "X-Trace-Id";
    private static final String ATTRIBUTE = GatewayRequestDiagnostics.class.getName();
    private static final Logger CANONICAL = LoggerFactory.getLogger("http.request");
    private static final Logger LOG = LoggerFactory.getLogger(GatewayRequestDiagnostics.class);
    private final long started = System.nanoTime();
    private final String correlationId = UUID.randomUUID().toString();
    private final ServerHttpRequest request;
    private final ServerHttpResponse response;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean errorRecorded = new AtomicBoolean();
    private volatile DiagnosticLogSnapshot snapshot;
    private volatile ServerWebExchange exchange;
    private volatile Throwable failure;
    private volatile boolean responseCancelled;

    private GatewayRequestDiagnostics(ServerHttpRequest request, ServerHttpResponse response) {
        this.request = request;
        this.response = response;
        this.snapshot = DiagnosticLogSnapshot.captureWithoutIdentity(correlationId);
    }

    static HttpHandler decorate(HttpHandler delegate) {
        return (request, response) -> Mono.defer(() -> {
            GatewayRequestDiagnostics state = new GatewayRequestDiagnostics(request, response);
            ServerHttpRequest internalRequest = request.mutate()
                    .headers(headers -> headers.set(HEADER, state.correlationId))
                    .build();
            response.beforeCommit(state::prepareHeaders);
            return Mono.defer(() -> delegate.handle(internalRequest, new GatewayDiagnosticResponse(response, state)))
                    .doOnError(state::failed)
                    .doFinally(state::complete)
                    .contextWrite(context -> context.put(GatewayRequestDiagnostics.class, state));
        });
    }

    static Mono<Void> bind(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(context -> {
            GatewayRequestDiagnostics state = context.get(GatewayRequestDiagnostics.class);
            state.exchange = exchange;
            // Agent instrumentation has entered the WebFlux server boundary here.
            state.snapshot = DiagnosticLogSnapshot.captureWithoutIdentity(state.correlationId);
            exchange.getAttributes().put(ATTRIBUTE, state);
            return chain.filter(exchange);
        });
    }

    static GatewayRequestDiagnostics find(ServerWebExchange exchange) {
        return exchange.getRequiredAttribute(ATTRIBUTE);
    }

    String correlationId() {
        return correlationId;
    }

    void failed(Throwable error) {
        failure = error;
    }

    void responseCancelled() {
        responseCancelled = true;
    }

    void finalError(Throwable error) {
        failed(error);
        if (errorRecorded.compareAndSet(false, true)) {
            inScope(() -> LOG.atError().addKeyValue("error.code", "SYS_UNEXPECTED")
                    .setCause(error).log("网关请求发生未处理异常"));
        }
    }

    private Mono<Void> prepareHeaders() {
        if (exchange != null
                && exchange.getAttribute(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR) != null
                && !correlationId.equals(response.getHeaders().getFirst(HEADER))) {
            inScope(() -> LOG.atError().addKeyValue("error.code", "SYS_GATEWAY_CORRELATION_MISMATCH")
                    .log("下游响应关联与网关入口不一致"));
        }
        response.getHeaders().set(HEADER, correlationId);
        if (snapshot.traceId() != null) {
            response.getHeaders().set(TRACE_HEADER, snapshot.traceId());
        } else {
            response.getHeaders().remove(TRACE_HEADER);
        }
        return Mono.empty();
    }

    private void complete(SignalType signal) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        HttpStatusCode status = response.getStatusCode();
        Integer observedStatus = response.isCommitted() ? (status == null ? 200 : status.value()) : null;
        String outcome;
        if (failure != null || responseCancelled || signal == SignalType.ON_ERROR) {
            outcome = "failure";
        } else if (signal == SignalType.CANCEL || observedStatus == null || observedStatus < 200) {
            outcome = "unknown";
        } else {
            outcome = observedStatus < 400 ? "success" : "failure";
        }
        inScope(() -> {
            var event = CANONICAL.atInfo()
                    .addKeyValue("http.request.method", request.getMethod().name())
                    .addKeyValue("url.path", request.getURI().getRawPath())
                    .addKeyValue("event.outcome", outcome)
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0);
            if (observedStatus != null) {
                event.addKeyValue("http.response.status_code", observedStatus);
            }
            if (request.getHeaders().getContentLength() >= 0) {
                event.addKeyValue("http.request.body.bytes", request.getHeaders().getContentLength());
            }
            if (response.isCommitted() && response.getHeaders().getContentLength() >= 0) {
                event.addKeyValue("http.response.body.bytes", response.getHeaders().getContentLength());
            }
            if (failure != null) {
                event.addKeyValue("error.type", failure.getClass().getName());
            }
            event.log("网关请求处理结束");
        });
    }

    private void inScope(Runnable action) {
        // Mask incidental authorization/MDC; restore the captured diagnostic-only state briefly.
        try (var absent = LoggingContextSnapshot.absent().openScope();
                var diagnostic = snapshot.openScope()) {
            action.run();
        }
    }
}
