package io.github.ande1922.moduvera.reference.catalog.catalog.application;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class ProductNotFoundException extends CodedException {

    public ProductNotFoundException(long productId) {
        super(new ErrorCode("catalog.product-not-found"), "Product was not found: " + productId);
    }
}
