package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import java.net.URI;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
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
        if (!(requestFactoryBuilder
                instanceof HttpComponentsClientHttpRequestFactoryBuilder httpComponents)) {
            throw new IllegalStateException("The catalog HTTP Service Client Group requires Apache HC5");
        }
        var settingsMapper = new HttpClientSettingsPropertyMapper(
                sslBundles.getIfAvailable(), defaultSettings.getIfAvailable());
        var noRetryRequestFactoryBuilder = httpComponents.withHttpClientCustomizer(
                HttpClientBuilder::disableAutomaticRetries);
        return groups -> groups.filterByName("catalog").forEachClient((group, client) -> client.requestFactory(
                noRetryRequestFactoryBuilder.build(settingsMapper.map(serviceClients.get(group.name())))));
    }

    @Bean
    CatalogApi orderCatalogClient(
            CatalogLookupTransport catalog,
            @Value("${spring.http.serviceclient.catalog.base-url}") URI catalogBaseUri,
            InternalAccessTokenProvider tokens) {
        requireUsableBaseUrl(catalogBaseUri);
        return new CatalogHttpClient(catalog, tokens);
    }

    private static void requireUsableBaseUrl(URI baseUrl) {
        String scheme = baseUrl.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUrl.getHost() == null
                || baseUrl.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "spring.http.serviceclient.catalog.base-url must be an absolute HTTP(S) URL");
        }
    }
}
