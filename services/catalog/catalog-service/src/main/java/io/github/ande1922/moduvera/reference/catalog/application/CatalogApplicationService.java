package io.github.ande1922.moduvera.reference.catalog.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.catalog.domain.ProductRepository;

public final class CatalogApplicationService implements CatalogApi {

    public static final PermissionCode READ_PRODUCT = new PermissionCode("catalog:read");

    private final ProductRepository products;
    private final UseCaseAuthorizer authorizer;

    public CatalogApplicationService(ProductRepository products, UseCaseAuthorizer authorizer) {
        this.products = products;
        this.authorizer = authorizer;
    }

    @Override
    public ProductSnapshot getProduct(GetProductQuery query) {
        authorizer.require(READ_PRODUCT);
        return products.findById(query.productId())
                .orElseThrow(() -> new ProductNotFoundException(query.productId()))
                .snapshot();
    }
}
