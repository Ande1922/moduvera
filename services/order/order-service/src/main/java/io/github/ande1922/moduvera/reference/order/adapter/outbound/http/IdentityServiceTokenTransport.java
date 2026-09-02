package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
interface IdentityServiceTokenTransport {

    @PostExchange("/internal/api/v1/service-token")
    TokenResponse issue(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody ServiceTokenRequest request);

    record ServiceTokenRequest(
            String tenantId, String audience, String initiatorType, String initiatorId) {}

    record TokenResponse(String accessToken, Instant expiresAt) {}
}
