package com.gaopc.platform.app.gateway;

import com.gaopc.platform.context.ExecutionContext;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class CorrelationGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = correlationId(exchange.getRequest().getHeaders().getFirst(HEADER));
        var request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String correlationId(String supplied) {
        if (!ExecutionContext.isValidCorrelationId(supplied)) {
            return UUID.randomUUID().toString();
        }
        return supplied;
    }
}
