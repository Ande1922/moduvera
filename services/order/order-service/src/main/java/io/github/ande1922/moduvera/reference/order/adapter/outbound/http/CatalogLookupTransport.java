package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
interface CatalogLookupTransport {

    @GetExchange("/internal/api/v1/catalog/products/{productId}")
    ProductSnapshot getProduct(
            @PathVariable long productId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Tenant-Id") String tenantId,
            @RequestHeader("X-Correlation-Id") String correlationId);
}
