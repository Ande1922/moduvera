package io.github.ande1922.moduvera.reference.app.gateway;

import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Owns errors that the Gateway can still turn into a complete Problem response. */
final class GatewayErrorHandler implements ErrorWebExceptionHandler, Ordered {

    private final GatewayProblemWriter problems;

    GatewayErrorHandler(GatewayProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        GatewayRequestDiagnostics state = GatewayRequestDiagnostics.find(exchange);
        state.failed(error);
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        if (error instanceof ErrorResponse response) {
            response.getHeaders().forEach((name, values) -> exchange.getResponse().getHeaders().put(name, values));
            return problems.write(exchange, response.getStatusCode(), "gateway.request-rejected",
                    "The Gateway could not complete this request");
        }
        state.finalError(error);
        return problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "system.unexpected",
                "An unexpected error occurred");
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
