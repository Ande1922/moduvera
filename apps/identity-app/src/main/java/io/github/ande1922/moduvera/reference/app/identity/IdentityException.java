package io.github.ande1922.moduvera.reference.app.identity;

import org.springframework.http.HttpStatus;

public final class IdentityException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public IdentityException(HttpStatus status, String code, String safeDetail) {
        super(safeDetail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
