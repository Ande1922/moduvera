package io.github.ande1922.moduvera.reference.app.gateway;

import java.net.URI;
import org.springframework.http.HttpStatus;
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

    Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String safeDetail) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, safeDetail);
        detail.setType(URI.create("urn:problem:" + code));
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        byte[] body = json.writeValueAsBytes(detail);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
