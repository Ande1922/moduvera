package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import java.net.URI;
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
@ImportHttpServices(group = "catalog", types = CatalogLookupTransport.class)
public class RemoteCatalogApiConfiguration {

    @Bean
    RestClientHttpServiceGroupConfigurer catalogNoRetryHttpServiceGroupConfigurer(
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpServiceClientProperties serviceClients,
            ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<HttpClientSettings> defaultSettings) {
        return OrderHttpServiceClientGroupSupport.noRetryGroupConfigurer(
                "catalog", requestFactoryBuilder, serviceClients, sslBundles, defaultSettings);
    }

    @Bean
    CatalogApi orderCatalogClient(
            CatalogLookupTransport catalog,
            @Value("${spring.http.serviceclient.catalog.base-url}") URI catalogBaseUri,
            InternalAccessTokenProvider tokens) {
        OrderHttpServiceClientGroupSupport.requireAbsoluteHttpBaseUrl(
                catalogBaseUri, "spring.http.serviceclient.catalog.base-url");
        return new CatalogHttpClient(catalog, tokens);
    }
}
