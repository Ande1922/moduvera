package com.gaopc.platform.app.gateway;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

final class IdentityTokenExchangeClient {

    private static final String EXCHANGE_PATH = "/internal/api/v1/token/exchange";
    private static final String ORDER_AUDIENCE = "order-service";

    private final WebClient identity;
    private final String gatewayAuthorization;
    private final Duration timeout;

    IdentityTokenExchangeClient(
            WebClient identity, String gatewayAuthorization, Duration timeout) {
        this.identity = identity;
        this.gatewayAuthorization = gatewayAuthorization;
        this.timeout = timeout;
    }

    Mono<String> exchange(String sessionToken, String correlationId) {
        return identity.post()
                .uri(EXCHANGE_PATH)
                .header(HttpHeaders.AUTHORIZATION, gatewayAuthorization)
                .header(CorrelationGlobalFilter.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TokenExchangeRequest(sessionToken, ORDER_AUDIENCE))
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 401) {
                        return response.releaseBody().then(Mono.error(new SessionRejectedException()));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(new IdentityUnavailableException()));
                    }
                    return response.bodyToMono(TokenResponse.class).flatMap(token -> {
                        if (token.accessToken() == null || token.accessToken().isBlank()) {
                            return Mono.error(new IdentityUnavailableException());
                        }
                        return Mono.just(token.accessToken());
                    });
                })
                .switchIfEmpty(Mono.error(new IdentityUnavailableException()))
                .timeout(timeout)
                .onErrorMap(
                        error -> !(error instanceof SessionRejectedException)
                                && !(error instanceof IdentityUnavailableException),
                        error -> new IdentityUnavailableException());
    }

    static final class SessionRejectedException extends RuntimeException {}

    static final class IdentityUnavailableException extends RuntimeException {}

    private record TokenExchangeRequest(String sessionToken, String audience) {}

    private record TokenResponse(String accessToken) {}
}
