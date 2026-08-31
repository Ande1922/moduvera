package io.github.ande1922.moduvera.migration;

public final class DatabaseIdentityException extends RuntimeException {

    public DatabaseIdentityException(String message) {
        super(message);
    }

    public DatabaseIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
