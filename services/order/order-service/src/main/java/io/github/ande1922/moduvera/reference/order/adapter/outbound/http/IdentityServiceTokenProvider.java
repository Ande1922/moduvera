package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Obtains a short-lived audience-scoped service JWT without exposing credentials to business code. */
public final class IdentityServiceTokenProvider implements InternalAccessTokenProvider {

    private final IdentityServiceTokenTransport identity;
    private final String serviceId;
    private final String serviceSecret;
    private final String audience;
    private final ServiceTokenCache tokens;

    public IdentityServiceTokenProvider(
            IdentityServiceTokenTransport identity,
            String serviceId,
            String serviceSecret,
            String audience,
            Clock clock,
            Duration refreshSkew,
            int tokenCacheMaxEntries) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
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
                context.requireTenantId().value(),
                context.initiator().type(),
                context.initiator().subjectId());
        return tokens.accessToken(key, () -> requestToken(key));
    }

    private ServiceTokenCache.Token requestToken(ServiceTokenCache.Key key) {
        var request = new IdentityServiceTokenTransport.ServiceTokenRequest(
                key.tenantId(), key.audience(), key.initiatorType().name(), key.initiatorId());
        try {
            IdentityServiceTokenTransport.TokenResponse response =
                    identity.issue(basic(serviceId, serviceSecret), request);
            if (response == null
                    || response.accessToken() == null
                    || response.accessToken().isBlank()
                    || response.expiresAt() == null) {
                throw new CatalogCallException("Identity returned an empty service token");
            }
            return new ServiceTokenCache.Token(response.accessToken(), response.expiresAt());
        } catch (CatalogCallException failure) {
            throw failure;
        } catch (RestClientResponseException failure) {
            throw new CatalogCallException(
                    "Identity rejected the service token request with HTTP "
                            + failure.getStatusCode().value(),
                    failure);
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
}
