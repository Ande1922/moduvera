package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "identity", types = IdentityServiceTokenTransport.class)
public class IdentityServiceTokenClientConfiguration {

    @Bean
    InternalAccessTokenProvider catalogAccessTokenProvider(
            IdentityServiceTokenTransport identity,
            @Value("${spring.http.serviceclient.identity.base-url}") URI identityBaseUri,
            @Value("${moduvera.reference.clients.identity.service-id}") String serviceId,
            @Value("${moduvera.reference.clients.identity.service-secret}") String serviceSecret,
            @Value("${moduvera.reference.clients.identity.token-refresh-skew:30s}") Duration refreshSkew,
            @Value("${moduvera.reference.clients.identity.token-cache-max-entries:1024}") int tokenCacheMaxEntries,
            Clock clock) {
        requireUsableBaseUrl(identityBaseUri);
        return new IdentityServiceTokenProvider(
                identity,
                serviceId,
                serviceSecret,
                "catalog-service",
                clock,
                refreshSkew,
                tokenCacheMaxEntries);
    }

    private static void requireUsableBaseUrl(URI baseUrl) {
        String scheme = baseUrl.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUrl.getHost() == null
                || baseUrl.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "spring.http.serviceclient.identity.base-url must be an absolute HTTP(S) URL");
        }
    }
}
