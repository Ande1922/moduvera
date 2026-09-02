package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "identity", types = IdentityServiceTokenTransport.class)
public class IdentityServiceTokenClientConfiguration {

    @Bean
    RestClientHttpServiceGroupConfigurer identityNoRetryHttpServiceGroupConfigurer(
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpServiceClientProperties serviceClients,
            ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<HttpClientSettings> defaultSettings) {
        return OrderHttpServiceClientGroupSupport.noRetryGroupConfigurer(
                "identity", requestFactoryBuilder, serviceClients, sslBundles, defaultSettings);
    }

    @Bean
    InternalAccessTokenProvider catalogAccessTokenProvider(
            IdentityServiceTokenTransport identity,
            @Value("${spring.http.serviceclient.identity.base-url}") URI identityBaseUri,
            @Value("${moduvera.reference.clients.identity.service-id}") String serviceId,
            @Value("${moduvera.reference.clients.identity.service-secret}") String serviceSecret,
            @Value("${moduvera.reference.clients.identity.token-refresh-skew:30s}") Duration refreshSkew,
            @Value("${moduvera.reference.clients.identity.token-cache-max-entries:1024}") int tokenCacheMaxEntries,
            Clock clock) {
        OrderHttpServiceClientGroupSupport.requireAbsoluteHttpBaseUrl(
                identityBaseUri, "spring.http.serviceclient.identity.base-url");
        return new IdentityServiceTokenProvider(
                identity,
                serviceId,
                serviceSecret,
                "catalog-service",
                clock,
                refreshSkew,
                tokenCacheMaxEntries);
    }
}
