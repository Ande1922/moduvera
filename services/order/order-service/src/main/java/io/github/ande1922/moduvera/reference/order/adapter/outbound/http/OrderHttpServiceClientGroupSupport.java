package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import java.net.URI;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

final class OrderHttpServiceClientGroupSupport {

    private OrderHttpServiceClientGroupSupport() {}

    static RestClientHttpServiceGroupConfigurer noRetryGroupConfigurer(
            String groupName,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpServiceClientProperties serviceClients,
            ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<HttpClientSettings> defaultSettings) {
        if (!(requestFactoryBuilder
                instanceof HttpComponentsClientHttpRequestFactoryBuilder httpComponents)) {
            throw new IllegalStateException(
                    "The " + groupName + " HTTP Service Client Group requires Apache HC5");
        }
        var settingsMapper = new HttpClientSettingsPropertyMapper(
                sslBundles.getIfAvailable(), defaultSettings.getIfAvailable());
        var noRetryRequestFactoryBuilder = httpComponents.withHttpClientCustomizer(
                HttpClientBuilder::disableAutomaticRetries);
        return groups -> groups.filterByName(groupName).forEachClient((group, client) -> client.requestFactory(
                noRetryRequestFactoryBuilder.build(settingsMapper.map(serviceClients.get(group.name())))));
    }

    static void requireAbsoluteHttpBaseUrl(URI baseUrl, String propertyName) {
        String scheme = baseUrl.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUrl.getHost() == null
                || baseUrl.getHost().isBlank()) {
            throw new IllegalArgumentException(propertyName + " must be an absolute HTTP(S) URL");
        }
    }
}
