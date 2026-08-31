package com.gaopc.platform.app.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class OrderSessionGatewayFilter implements GatewayFilter {

    private final IdentityTokenExchangeClient identities;
    private final GatewayProblemWriter problems;

    OrderSessionGatewayFilter(IdentityTokenExchangeClient identities, GatewayProblemWriter problems) {
        this.identities = identities;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return problems.write(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "gateway.session-required",
                    "A browser session is required");
        }
        String sessionToken = authorization.substring(7);
        if (!sessionToken.matches("[A-Za-z0-9_-]{43}")) {
            return problems.write(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "gateway.session-invalid",
                    "The browser session is invalid");
        }

        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(CorrelationGlobalFilter.HEADER);
        return identities
                .exchange(sessionToken, correlationId)
                .flatMap(internalJwt -> chain.filter(withInternalJwt(exchange, internalJwt)))
                .onErrorResume(IdentityTokenExchangeClient.SessionRejectedException.class, error ->
                        problems.write(
                                exchange,
                                HttpStatus.UNAUTHORIZED,
                                "gateway.session-rejected",
                                "The browser session is invalid or expired"))
                .onErrorResume(IdentityTokenExchangeClient.IdentityUnavailableException.class, error ->
                        problems.write(
                                exchange,
                                HttpStatus.BAD_GATEWAY,
                                "gateway.identity-unavailable",
                                "Identity is unavailable"));
    }

    private static ServerWebExchange withInternalJwt(
            ServerWebExchange exchange, String internalJwt) {
        var request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove("Tenant-Id");
                    headers.setBearerAuth(internalJwt);
                })
                .build();
        return exchange.mutate().request(request).build();
    }
}
