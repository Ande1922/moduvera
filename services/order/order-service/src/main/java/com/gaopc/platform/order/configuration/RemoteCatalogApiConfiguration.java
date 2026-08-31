package com.gaopc.platform.order.configuration;

import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.order.infrastructure.http.CatalogHttpClient;
import com.gaopc.platform.order.infrastructure.http.IdentityServiceTokenProvider;
import com.gaopc.platform.order.infrastructure.http.InternalAccessTokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
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
            @Value("${platform.clients.identity.base-url}") URI identityBaseUri,
            @Value("${platform.clients.identity.service-id}") String serviceId,
            @Value("${platform.clients.identity.service-secret}") String serviceSecret) {
        return new IdentityServiceTokenProvider(
                restClients, identityBaseUri, serviceId, serviceSecret, "catalog-service");
    }

    @Bean
    @SuppressWarnings("PMD.CloseResource")
    HttpClient orderCatalogJdkHttpClient(
            @Value("${platform.clients.catalog.timeout:2s}") Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Bean
    CatalogApi orderCatalogClient(
            RestClient.Builder builder,
            HttpClient orderCatalogJdkHttpClient,
            @Value("${platform.clients.catalog.base-url}") URI baseUri,
            @Value("${platform.clients.catalog.timeout:2s}") Duration timeout,
            InternalAccessTokenProvider tokens) {
        var requestFactory = new JdkClientHttpRequestFactory(orderCatalogJdkHttpClient);
        requestFactory.setReadTimeout(timeout);
        return new CatalogHttpClient(builder.requestFactory(requestFactory), baseUri, tokens);
    }
}
