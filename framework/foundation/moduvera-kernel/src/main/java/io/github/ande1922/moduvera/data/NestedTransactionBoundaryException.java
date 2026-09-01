package io.github.ande1922.moduvera.data;

public final class NestedTransactionBoundaryException extends IllegalStateException {

    public NestedTransactionBoundaryException() {
        super("a transaction boundary must be the explicit top-level local database transaction");
    }
}
