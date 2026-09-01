package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class CatalogHttpClient implements CatalogApi {

    private final RestClient http;
    private final InternalAccessTokenProvider tokens;

    public CatalogHttpClient(RestClient.Builder builder, URI baseUri, InternalAccessTokenProvider tokens) {
        this.http = builder.baseUrl(baseUri.toString()).build();
        this.tokens = tokens;
    }

    @Override
    public ProductSnapshot getProduct(GetProductQuery query) {
        var context = ExecutionContextHolder.require();
        try {
            ProductSnapshot snapshot = http.get()
                    .uri("/internal/api/v1/catalog/products/{productId}", query.productId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + requireToken())
                    .header("Tenant-Id", context.tenantId().value())
                    .header("X-Correlation-Id", context.correlationId())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new CatalogCallException(
                                "Catalog rejected the product lookup with HTTP " + response.getStatusCode().value());
                    })
                    .body(ProductSnapshot.class);
            if (snapshot == null) {
                throw new CatalogCallException("Catalog returned an empty product snapshot");
            }
            return snapshot;
        } catch (CatalogCallException failure) {
            throw failure;
        } catch (RestClientException failure) {
            throw new CatalogCallException("Catalog product lookup failed", failure);
        }
    }

    private String requireToken() {
        String token = tokens.accessToken();
        if (token == null || token.isBlank()) {
            throw new CatalogCallException("Internal Catalog access token is unavailable");
        }
        return token;
    }
}
