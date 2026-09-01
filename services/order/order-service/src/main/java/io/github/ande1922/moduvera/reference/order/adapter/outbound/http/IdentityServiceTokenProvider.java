package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Obtains a short-lived audience-scoped service JWT without exposing credentials to business code. */
public final class IdentityServiceTokenProvider implements InternalAccessTokenProvider {

    private final RestClient identity;
    private final String serviceId;
    private final String serviceSecret;
    private final String audience;
    private final ServiceTokenCache tokens;

    public IdentityServiceTokenProvider(
            RestClient.Builder builder,
            URI identityBaseUri,
            String serviceId,
            String serviceSecret,
            String audience,
            Clock clock,
            Duration refreshSkew,
            int tokenCacheMaxEntries) {
        this.identity = builder.baseUrl(identityBaseUri.toString()).build();
        this.serviceId = requireConfiguration(serviceId, "serviceId");
        this.serviceSecret = requireConfiguration(serviceSecret, "serviceSecret");
        this.audience = requireConfiguration(audience, "audience");
        this.tokens = new ServiceTokenCache(clock, refreshSkew, tokenCacheMaxEntries);
    }

    @Override
    public String accessToken() {
        var context = ExecutionContextHolder.require();
        var key = new ServiceTokenCache.Key(
                serviceId,
                audience,
                context.tenantId().value(),
                context.initiator().type(),
                context.initiator().subjectId());
        return tokens.accessToken(key, () -> requestToken(key));
    }

    private ServiceTokenCache.Token requestToken(ServiceTokenCache.Key key) {
        var request = new ServiceTokenRequest(
                key.tenantId(), key.audience(), key.initiatorType().name(), key.initiatorId());
        try {
            TokenResponse response = identity.post()
                    .uri("/internal/api/v1/service-token")
                    .header(HttpHeaders.AUTHORIZATION, basic(serviceId, serviceSecret))
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                        throw new CatalogCallException(
                                "Identity rejected the service token request with HTTP "
                                        + httpResponse.getStatusCode().value());
                    })
                    .body(TokenResponse.class);
            if (response == null
                    || response.accessToken() == null
                    || response.accessToken().isBlank()
                    || response.expiresAt() == null) {
                throw new CatalogCallException("Identity returned an empty service token");
            }
            return new ServiceTokenCache.Token(response.accessToken(), response.expiresAt());
        } catch (CatalogCallException failure) {
            throw failure;
        } catch (RestClientException failure) {
            throw new CatalogCallException("Identity service token request failed", failure);
        }
    }

    private static String basic(String serviceId, String secret) {
        String value = serviceId + ":" + secret;
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String requireConfiguration(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return value;
    }

    private record ServiceTokenRequest(
            String tenantId, String audience, String initiatorType, String initiatorId) {}

    private record TokenResponse(String accessToken, Instant expiresAt) {}
}
