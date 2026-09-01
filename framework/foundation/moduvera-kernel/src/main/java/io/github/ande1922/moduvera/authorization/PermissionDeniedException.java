package io.github.ande1922.moduvera.authorization;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class PermissionDeniedException extends CodedException {

    private static final ErrorCode CODE = new ErrorCode("security.permission-denied");

    public PermissionDeniedException(PermissionCode permission) {
        super(CODE, "Required permission is missing: " + permission.value());
    }
}
