package io.github.ande1922.moduvera.reference.order.infrastructure.http;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class CatalogCallException extends CodedException {

    public CatalogCallException(String safeDetail) {
        super(new ErrorCode("order.catalog-unavailable"), safeDetail);
    }

    public CatalogCallException(String safeDetail, Throwable cause) {
        super(new ErrorCode("order.catalog-unavailable"), safeDetail, cause);
    }
}
