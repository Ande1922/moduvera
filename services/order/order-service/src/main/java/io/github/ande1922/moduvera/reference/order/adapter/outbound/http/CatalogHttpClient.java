package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class CatalogHttpClient implements CatalogApi {

    private final CatalogLookupTransport catalog;
    private final InternalAccessTokenProvider tokens;

    public CatalogHttpClient(CatalogLookupTransport catalog, InternalAccessTokenProvider tokens) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.tokens = tokens;
    }

    @Override
    public ProductSnapshot getProduct(GetProductQuery query) {
        var context = ExecutionContextHolder.require();
        try {
            ProductSnapshot snapshot = catalog.getProduct(
                    query.productId(),
                    "Bearer " + requireToken(),
                    context.tenantId().value(),
                    context.correlationId());
            if (snapshot == null) {
                throw new CatalogCallException("Catalog returned an empty product snapshot");
            }
            return snapshot;
        } catch (CatalogCallException failure) {
            throw failure;
        } catch (RestClientResponseException failure) {
            throw new CatalogCallException(
                    "Catalog rejected the product lookup with HTTP "
                            + failure.getStatusCode().value(),
                    failure);
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
