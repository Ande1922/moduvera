package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RemoteCatalogApiConfiguration {

    @Bean
    InternalAccessTokenProvider catalogAccessTokenProvider(
            RestClient.Builder restClients,
            @Value("${moduvera.reference.clients.identity.base-url}") URI identityBaseUri,
            @Value("${moduvera.reference.clients.identity.service-id}") String serviceId,
            @Value("${moduvera.reference.clients.identity.service-secret}") String serviceSecret,
            @Value("${moduvera.reference.clients.identity.token-refresh-skew:30s}") Duration refreshSkew,
            @Value("${moduvera.reference.clients.identity.token-cache-max-entries:1024}") int tokenCacheMaxEntries,
            Clock clock) {
        return new IdentityServiceTokenProvider(
                restClients,
                identityBaseUri,
                serviceId,
                serviceSecret,
                "catalog-service",
                clock,
                refreshSkew,
                tokenCacheMaxEntries);
    }

    @Bean
    @SuppressWarnings("PMD.CloseResource")
    HttpClient orderCatalogJdkHttpClient(
            @Value("${moduvera.reference.clients.catalog.timeout:2s}") Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Bean
    CatalogApi orderCatalogClient(
            RestClient.Builder builder,
            HttpClient orderCatalogJdkHttpClient,
            @Value("${moduvera.reference.clients.catalog.base-url}") URI baseUri,
            @Value("${moduvera.reference.clients.catalog.timeout:2s}") Duration timeout,
            InternalAccessTokenProvider tokens) {
        var requestFactory = new JdkClientHttpRequestFactory(orderCatalogJdkHttpClient);
        requestFactory.setReadTimeout(timeout);
        return new CatalogHttpClient(builder.requestFactory(requestFactory), baseUri, tokens);
    }
}
