package com.gaopc.platform.catalog.inbound.http;

import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.catalog.api.GetProductQuery;
import com.gaopc.platform.catalog.api.ProductSnapshot;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/api/v1/catalog")
public class CatalogHttpController {

    private final CatalogApi catalog;

    public CatalogHttpController(CatalogApi catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products/{productId}")
    public ProductSnapshot getProduct(@PathVariable @Positive long productId) {
        return catalog.getProduct(new GetProductQuery(productId));
    }
}
