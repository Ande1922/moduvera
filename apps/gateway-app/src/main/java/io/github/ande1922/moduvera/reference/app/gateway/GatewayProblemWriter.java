package io.github.ande1922.moduvera.reference.app.gateway;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

final class GatewayProblemWriter {

    private final ObjectMapper json;

    GatewayProblemWriter(ObjectMapper json) {
        this.json = json;
    }

    Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String safeDetail) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, safeDetail);
        detail.setType(URI.create("urn:problem:" + code));
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        if (knownStatus != null) {
            detail.setTitle(knownStatus.getReasonPhrase());
        }
        detail.setProperty("code", code);
        detail.setProperty("correlationId", GatewayRequestDiagnostics.find(exchange).correlationId());
        byte[] body = json.writeValueAsBytes(detail);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
